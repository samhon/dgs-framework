/*
 * Copyright 2021 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.graphql.dgs.metrics.micrometer.utils

import com.netflix.graphql.dgs.metrics.DgsMetrics
import com.netflix.graphql.dgs.metrics.micrometer.DgsMeterRegistrySupplier
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.language.Document
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.actuate.metrics.AutoTimer
import org.springframework.cache.annotation.CacheConfig
import org.springframework.cache.annotation.Cacheable
import java.util.*

@CacheConfig(cacheManager = CacheableQuerySignatureRepository.QUERY_SIG_CACHE_MANAGER)
open class CacheableQuerySignatureRepository(
    private val meterRegistrySupplier: DgsMeterRegistrySupplier,
    private val autoTimer: AutoTimer,
    private val timerName: String
) : QuerySignatureRepository, InitializingBean {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(CacheableQuerySignatureRepository::class.java)
        const val QUERY_SIG_CACHE_MANAGER = "dgs-querySignatureRepositoryCacheManager"
        const val QUERY_SIG_CACHE = "dgs-querySignatureCache"
    }

    lateinit var meterRegistry: MeterRegistry

    override fun get(
        document: Document,
        parameters: InstrumentationExecutionParameters
    ): Optional<QuerySignatureRepository.QuerySignature> {
        val timerSample = Timer.start(meterRegistry)
        var tags = Tags.empty()
        val queryHash = QuerySignatureRepository.queryHash(parameters.query)
        return try {
            val result = Optional.ofNullable(
                getCacheableQuerySignature(
                    queryHash,
                    parameters.operation,
                    document
                )
            )
            tags = tags.and(DgsMetrics.CommonTags.SUCCESS.tag)
            return result
        } catch (error: Throwable) {
            tags = tags.and(DgsMetrics.CommonTags.FAILURE.tag)
            log.error(
                "Failed to fetch query signature from cache, query [hash:{}, name:{}].",
                queryHash, parameters.operation
            )
            Optional.empty()
        } finally {
            timerSample.stop(
                autoTimer
                    .builder(timerName)
                    .tags("method", javaClass.simpleName + ".get")
                    .tags(tags)
                    .register(meterRegistry)
            )
        }
    }

    @Cacheable(cacheNames = [QUERY_SIG_CACHE], key = "p0 and p1")
    open fun getCacheableQuerySignature(
        queryHash: String,
        queryName: String?,
        document: Document
    ): QuerySignatureRepository.QuerySignature {
        return QuerySignatureRepository.computeSignature(document, queryName)
    }

    override fun afterPropertiesSet() {
        this.meterRegistry = meterRegistrySupplier.get()
    }
}
