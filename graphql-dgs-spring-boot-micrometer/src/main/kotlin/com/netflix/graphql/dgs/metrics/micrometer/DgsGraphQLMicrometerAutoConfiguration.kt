package com.netflix.graphql.dgs.metrics.micrometer

import com.netflix.graphql.dgs.metrics.micrometer.DgsGraphQLMicrometerAutoConfiguration.Companion.AUTO_CONF_PREFIX
import com.netflix.graphql.dgs.metrics.micrometer.dataloader.DgsDataLoaderInstrumentationProvider
import com.netflix.graphql.dgs.metrics.micrometer.tagging.*
import com.netflix.graphql.dgs.metrics.micrometer.utils.CacheableQuerySignatureRepository
import com.netflix.graphql.dgs.metrics.micrometer.utils.CacheableQuerySignatureRepository.Companion.QUERY_SIG_CACHE
import com.netflix.graphql.dgs.metrics.micrometer.utils.CacheableQuerySignatureRepository.Companion.QUERY_SIG_CACHE_MANAGER
import com.netflix.graphql.dgs.metrics.micrometer.utils.QuerySignatureRepository
import com.netflix.spectator.api.patterns.CardinalityLimiters
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCache
import org.springframework.cache.support.SimpleCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * [Auto-configuration][org.springframework.boot.autoconfigure.EnableAutoConfiguration] for instrumentation of Spring GraphQL
 * endpoints.
 */
@AutoConfigureAfter(CompositeMeterRegistryAutoConfiguration::class)
@ConditionalOnClass(MetricsAutoConfiguration::class, MeterRegistry::class)
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = AUTO_CONF_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DgsGraphQLMetricsProperties::class)
open class DgsGraphQLMicrometerAutoConfiguration {

    companion object {
        const val AUTO_CONF_PREFIX = "management.metrics.dgs-graphql"
    }

    @Bean
    @ConditionalOnMissingBean
    open fun meterRegistrySupplier(meterRegistryProvider: ObjectProvider<MeterRegistry>): DgsMeterRegistrySupplier {
        return DefaultMeterRegistrySupplier(meterRegistryProvider)
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "$AUTO_CONF_PREFIX.tag-customizers.outcome",
        name = ["enabled"], havingValue = "true", matchIfMissing = true
    )
    open fun simpleGqlOutcomeTagCustomizer(): SimpleGqlOutcomeTagCustomizer {
        return SimpleGqlOutcomeTagCustomizer()
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "$AUTO_CONF_PREFIX.instrumentation",
        name = ["enabled"], havingValue = "true", matchIfMissing = true
    )
    open fun metricsInstrumentation(
        meterRegistrySupplier: DgsMeterRegistrySupplier,
        tagsProvider: DgsGraphQLMetricsTagsProvider,
        properties: DgsGraphQLMetricsProperties,
        querySignatureRepository: QuerySignatureRepository,
        @Qualifier("graphqlCardinalityLimiterProvider") graphqlCardinalityLimiterProvider: CardinalityLimiterProvider
    ): DgsGraphQLMetricsInstrumentation {
        return DgsGraphQLMetricsInstrumentation(
            meterRegistrySupplier,
            tagsProvider,
            properties.autotime,
            querySignatureRepository,
            graphqlCardinalityLimiterProvider
        )
    }

    @Bean()
    @ConditionalOnMissingBean
    open fun graphqlCardinalityLimiterProvider(properties: DgsGraphQLMetricsProperties): CardinalityLimiterProvider {
        // TODO[BGP]: Have the limiter max fetched from the properties.
        return CardinalityLimiterProvider {
            CardinalityLimiters.first(100)
        }
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "$AUTO_CONF_PREFIX.data-loader-instrumentation",
        name = ["enabled"], havingValue = "true", matchIfMissing = true
    )
    open fun dataLoaderInstrumentationProvider(
        meterRegistrySupplier: DgsMeterRegistrySupplier
    ): DgsDataLoaderInstrumentationProvider {
        return DgsDataLoaderInstrumentationProvider(meterRegistrySupplier)
    }

    @Bean
    open fun collatedMetricsTagsProvider(
        contextualTagCustomizer: Collection<DgsContextualTagCustomizer>,
        executionTagCustomizer: Collection<DgsExecutionTagCustomizer>,
        fieldFetchTagCustomizer: Collection<DgsFieldFetchTagCustomizer>
    ): DgsGraphQLMetricsTagsProvider {
        return DgsGraphQLCollatedMetricsTagsProvider(
            contextualTagCustomizer,
            executionTagCustomizer,
            fieldFetchTagCustomizer
        )
    }

    @Configuration
    @ConditionalOnMissingBean(value = [QuerySignatureRepository::class])
    @EnableCaching
    open class CachedQuerySignatureRepositoryConfiguration {

        @Bean(QUERY_SIG_CACHE_MANAGER)
        @ConditionalOnMissingBean(name = [QUERY_SIG_CACHE_MANAGER])
        open fun querySignatureRepositoryCacheManager(): CacheManager {
            val cacheManager = SimpleCacheManager()
            cacheManager.setCaches(
                listOf(ConcurrentMapCache(QUERY_SIG_CACHE))
            )
            return cacheManager
        }

        @Bean
        open fun cacheableQuerySignatureRepository(
            meterRegistrySupplier: DgsMeterRegistrySupplier,
            properties: DgsGraphQLMetricsProperties
        ): QuerySignatureRepository {
            return CacheableQuerySignatureRepository(
                meterRegistrySupplier,
                properties.autotime,
                "dgs.method.latency"
            )
        }
    }

    internal class DefaultMeterRegistrySupplier(
        private val meterRegistryProvider: ObjectProvider<MeterRegistry>
    ) : DgsMeterRegistrySupplier {

        companion object {
            /** Fallback Micrometer [MeterRegistry] used in case the [ObjectProvider] doesn't define one. */
            private val DEFAULT_METER_REGISTRY = SimpleMeterRegistry()
        }

        override fun get(): MeterRegistry {
            return meterRegistryProvider.ifAvailable ?: DEFAULT_METER_REGISTRY
        }
    }
}
