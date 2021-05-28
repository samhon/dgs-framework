package com.netflix.graphql.dgs.metrics.micrometer

import com.netflix.graphql.dgs.metrics.DgsMetrics.GqlMetric
import com.netflix.graphql.dgs.metrics.DgsMetrics.GqlTag
// import com.netflix.graphql.dgs.metrics.micrometer.DgsGraphQLMetricsInstrumentationUtils.resolveDataFetcherTagValue
// import com.netflix.graphql.dgs.metrics.micrometer.DgsGraphQLMetricsInstrumentationUtils.sanitizeErrorPaths
// import com.netflix.graphql.dgs.metrics.micrometer.DgsGraphQLMetricsInstrumentationUtils.shouldIgnoreTag
import com.netflix.graphql.dgs.metrics.micrometer.tagging.DgsGraphQLMetricsTagsProvider
import com.netflix.graphql.dgs.metrics.micrometer.utils.QuerySignatureRepository
import graphql.ExecutionResult
import graphql.GraphQLError
import graphql.InvalidSyntaxError
import graphql.analysis.QueryTraverser
import graphql.analysis.QueryVisitorFieldEnvironment
import graphql.analysis.QueryVisitorStub
import graphql.execution.instrumentation.*
import graphql.execution.instrumentation.SimpleInstrumentationContext.whenCompleted
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationValidationParameters
import graphql.schema.DataFetcher
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.validation.ValidationError
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.metrics.AutoTimer
import java.util.*
import java.util.Optional.empty
import java.util.Optional.ofNullable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class DgsGraphQLMetricsInstrumentation(
    private val registrySupplier: DgsMeterRegistrySupplier,
    private val tagsProvider: DgsGraphQLMetricsTagsProvider,
    private val autoTimer: AutoTimer,
    private val querySignatureRepository: QuerySignatureRepository,
    private val cardinalityLimiterProvider: CardinalityLimiterProvider,
) : SimpleInstrumentation() {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(DgsGraphQLMetricsInstrumentation::class.java)
    }

    override fun createState(): InstrumentationState {
        return MetricsInstrumentationState(
            registrySupplier.get(),
            operationNameCardinalityLimiter = cardinalityLimiterProvider.limiter(),
            querySigHashCardinalityLimiter = cardinalityLimiterProvider.limiter()
        )
    }

    override fun beginExecution(parameters: InstrumentationExecutionParameters): InstrumentationContext<ExecutionResult> {
        val state: MetricsInstrumentationState = parameters.getInstrumentationState()
        state.startTimer()

        state.operationName = ofNullable(parameters.operation)
        state.operation = ofNullable(parameters.schema.queryType.name)

        return object : SimpleInstrumentationContext<ExecutionResult>() {

            override fun onDispatched(result: CompletableFuture<ExecutionResult>?) {
                super.onDispatched(result)
            }

            override fun onCompleted(result: ExecutionResult, exc: Throwable?) {
                state.stopTimer(
                    autoTimer
                        .builder(GqlMetric.QUERY.key)
                        .tags(tagsProvider.getContextualTags())
                        .tags(tagsProvider.getExecutionTags(parameters, result, exc))
                        .tags(state.tags())
                )
            }
        }
    }

    override fun instrumentDocumentAndVariables(
        documentAndVariables: DocumentAndVariables,
        parameters: InstrumentationExecutionParameters
    ): DocumentAndVariables {
        return documentAndVariables
    }

    override fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters
    ): CompletableFuture<ExecutionResult> {

        val state = parameters.getInstrumentationState<MetricsInstrumentationState>()
        val tags =
            Tags.empty()
                .and(tagsProvider.getContextualTags())
                .and(tagsProvider.getExecutionTags(parameters, executionResult, null))
                .and(state.tags())

        ErrorUtils.sanitizeErrorPaths(executionResult).forEach {
            registrySupplier
                .get()
                .counter(
                    GqlMetric.ERROR.key,
                    tags.and(GqlTag.PATH.key, it.path)
                        .and(GqlTag.ERROR_CODE.key, it.type)
                        .and(GqlTag.ERROR_DETAIL.key, it.detail)
                ).increment()
        }

        return CompletableFuture.completedFuture(executionResult)
    }

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters
    ): DataFetcher<*> {
        val state: MetricsInstrumentationState = parameters.getInstrumentationState()
        val gqlField = TagUtils.resolveDataFetcherTagValue(parameters)
        if (parameters.isTrivialDataFetcher || TagUtils.shouldIgnoreTag(gqlField)) {
            return dataFetcher
        }

        return DataFetcher { environment ->
            val registry = registrySupplier.get()
            val baseTags =
                Tags.of(GqlTag.FIELD.key, gqlField)
                    .and(tagsProvider.getContextualTags())
                    .and(state.tags())

            val sampler = Timer.start(registry)
            try {
                val result = dataFetcher.get(environment)
                if (result is CompletionStage<*>) {
                    result.whenComplete { _, error ->
                        recordDataFetcherMetrics(
                            registry,
                            sampler,
                            parameters,
                            error,
                            baseTags
                        )
                    }
                } else {
                    recordDataFetcherMetrics(registry, sampler, parameters, null, baseTags)
                }
                result
            } catch (throwable: Throwable) {
                recordDataFetcherMetrics(registry, sampler, parameters, throwable, baseTags)
                throw throwable
            }
        }
    }

    /**
     * Port the implementation from MaxQueryComplexityInstrumentation in graphql-java and store the computed complexity
     * in the MetricsInstrumentationState for access to add tags to metrics.
     */
    override fun beginValidation(parameters: InstrumentationValidationParameters): InstrumentationContext<List<ValidationError>> {
        return whenCompleted { errors, throwable ->
            if (errors != null && errors.isNotEmpty() || throwable != null) {
                return@whenCompleted
            }
            val state: MetricsInstrumentationState = parameters.getInstrumentationState()
            // compute the Query signatures.
            if (parameters.document != null) {
                state.querySignature = querySignatureRepository.get(parameters.document, parameters)
            }
            // compute the query complexity.
            state.queryComplexity = ComplexityUtils.resolveComplexity(parameters)
        }
    }

    private fun recordDataFetcherMetrics(
        registry: MeterRegistry,
        timerSampler: Timer.Sample,
        parameters: InstrumentationFieldFetchParameters,
        error: Throwable?,
        baseTags: Iterable<Tag>
    ) {
        val recordedTags = Tags
            .of(baseTags)
            .and(tagsProvider.getFieldFetchTags(parameters, error))

        timerSampler.stop(autoTimer.builder(GqlMetric.RESOLVER.key).tags(recordedTags).register(registry))
    }

    class MetricsInstrumentationState(
        private val registry: MeterRegistry,
        private val operationNameCardinalityLimiter: java.util.function.Function<String, String>,
        private val querySigHashCardinalityLimiter: java.util.function.Function<String, String>
    ) : InstrumentationState {
        private var timerSample: Optional<Timer.Sample> = empty()

        var queryComplexity: Optional<Int> = empty()
        var operation: Optional<String> = empty()
        var operationName: Optional<String> = empty()
        var querySignature: Optional<QuerySignatureRepository.QuerySignature> = empty()

        fun startTimer() {
            this.timerSample = Optional.of(Timer.start(this.registry))
        }

        fun stopTimer(timer: Timer.Builder) {
            this.timerSample.ifPresent { it.stop(timer.register(this.registry)) }
        }

        internal fun tags(): Tags {
            return Tags
                .of(
                    GqlTag.QUERY_COMPLEXITY.key,
                    queryComplexity.map { it.toString() }.orElse(TagUtils.TAG_VALUE_NONE)
                )
                .and(
                    GqlTag.OPERATION.key,
                    operation.map { it.toUpperCase() }.orElse(TagUtils.TAG_VALUE_NONE)
                )
                .and(
                    GqlTag.OPERATION_NAME.key,
                    operationName
                        .map { operationNameCardinalityLimiter.apply(it) }
                        .orElse(TagUtils.TAG_VALUE_ANONYMOUS)
                )
                .and(
                    GqlTag.QUERY_SIG_HASH.key,
                    querySignature
                        .map { querySigHashCardinalityLimiter.apply(it.hash) }
                        .orElse(TagUtils.TAG_VALUE_NONE)
                )
        }
    }

    internal object ComplexityUtils {

        private val queryComplexityBuckets = listOf(5, 10, 25, 50, 100, 200, 500, 1000, 2000, 5000, 10000)

        fun resolveComplexity(parameters: InstrumentationValidationParameters): Optional<Int> {
            try {
                val queryTraverser: QueryTraverser = newQueryTraverser(parameters)
                val valuesByParent: MutableMap<QueryVisitorFieldEnvironment?, Int?> =
                    LinkedHashMap<QueryVisitorFieldEnvironment?, Int?>()
                queryTraverser.visitPostOrder(object : QueryVisitorStub() {
                    override fun visitField(env: QueryVisitorFieldEnvironment?) {
                        val childComplexity = valuesByParent.getOrDefault(env, 0)
                        val value = calculateComplexity(env!!, childComplexity!!)
                        valuesByParent.compute(env.parentEnvironment) { _, oldValue -> ofNullable(oldValue).orElse(0) + value }
                    }
                })
                val totalComplexity = valuesByParent.getOrDefault(null, 0)
                return ofNullable(queryComplexityBuckets.filter { totalComplexity!! < it }.minOrNull())
            } catch (error: Throwable) {
                log.error("Unable to compute the query complexity!", error)
                return empty()
            }
        }

        /**
         * Port from MaxQueryComplexityInstrumentation in graphql-java
         */
        fun calculateComplexity(
            queryVisitorFieldEnvironment: QueryVisitorFieldEnvironment,
            childComplexity: Int,
            fieldComplexityCalculator: (Int) -> Int = { cx -> 1 + cx }
        ): Int {
            if (queryVisitorFieldEnvironment.isTypeNameIntrospectionField) {
                return 0
            }
            return fieldComplexityCalculator(childComplexity)
        }

        private fun newQueryTraverser(parameters: InstrumentationValidationParameters): QueryTraverser {
            return QueryTraverser
                .newQueryTraverser()
                .schema(parameters.schema)
                .document(parameters.document)
                .operationName(parameters.operation)
                .variables(parameters.variables)
                .build()
        }
    }

    internal object TagUtils {
        private val instrumentationIgnores = setOf("__typename", "__Schema", "__Type")

        const val TAG_VALUE_UNKNOWN = "unknown"
        const val TAG_VALUE_NONE = "none"
        const val TAG_VALUE_ANONYMOUS = "anonymous"

        fun resolveDataFetcherTagValue(parameters: InstrumentationFieldFetchParameters): String {
            val type = parameters.executionStepInfo.parent.type
            val parentType = if (type is GraphQLNonNull) {
                type.wrappedType as GraphQLObjectType
            } else {
                type as GraphQLObjectType
            }

            return "${parentType.name}.${parameters.executionStepInfo.field.singleField.name}"
        }

        fun shouldIgnoreTag(tag: String): Boolean {
            return instrumentationIgnores.find { tag.contains(it) } != null
        }
    }

    internal object ErrorUtils {

        fun sanitizeErrorPaths(executionResult: ExecutionResult): Collection<ErrorTagValues> {
            var dedupeErrorPaths: Map<String, ErrorTagValues> = emptyMap()
            executionResult.errors.forEach { error ->
                val errorPath: List<Any>
                val errorType: String
                val errorDetail = errorDetailExtension(error)
                when (error) {
                    is ValidationError -> {
                        errorPath = error.queryPath ?: emptyList()
                        errorType = errorType(error)
                    }
                    is InvalidSyntaxError -> {
                        errorPath = emptyList()
                        errorType = errorType(error)
                    }
                    else -> {
                        errorPath = error.path ?: emptyList()
                        errorType = errorTypeExtension(error)
                    }
                }
                val sanitizedPath = errorPath.map { iter ->
                    if (iter.toString().toIntOrNull() != null) "number"
                    else iter.toString()
                }.toString()
                // in case of batch loaders, eliminate duplicate instances of the same error at different indices
                if (!dedupeErrorPaths.contains(sanitizedPath)) {
                    dedupeErrorPaths = dedupeErrorPaths
                        .plus(Pair(sanitizedPath, ErrorTagValues(sanitizedPath, errorType, errorDetail)))
                }
            }
            return dedupeErrorPaths.values
        }

        private fun <T : GraphQLError> errorType(error: T): String {
            return error.errorType?.toString() ?: TagUtils.TAG_VALUE_UNKNOWN
        }

        private fun <T : GraphQLError> errorTypeExtension(error: T): String {
            return extension(error, "errorType", TagUtils.TAG_VALUE_UNKNOWN)
        }

        private fun <T : GraphQLError> errorDetailExtension(error: T): String {
            return extension(error, "errorDetail", TagUtils.TAG_VALUE_NONE)
        }

        private fun <T : GraphQLError> extension(error: T, key: String, default: String): String {
            return error.extensions?.get(key)?.toString() ?: default
        }

        internal data class ErrorTagValues(val path: String, val type: String, val detail: String)
    }
}
