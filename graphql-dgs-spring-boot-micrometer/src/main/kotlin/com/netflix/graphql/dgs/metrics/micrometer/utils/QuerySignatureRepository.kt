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

import com.netflix.graphql.dgs.Internal
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.language.AstPrinter
import graphql.language.AstSignature
import graphql.language.Document
import org.apache.commons.codec.digest.DigestUtils
import java.util.*

@FunctionalInterface
@Internal
fun interface QuerySignatureRepository {

    companion object {
        internal fun queryHash(query: String): String = DigestUtils.sha256Hex(query)

        internal fun computeSignature(
            document: Document,
            operationName: String?
        ): QuerySignature {
            val querySignatureDoc = AstSignature().signatureQuery(document, operationName)
            val querySignature = AstPrinter.printAst(querySignatureDoc)
            val querySigHash = DigestUtils.sha256Hex(querySignature)
            return QuerySignature(value = querySignature, hash = querySigHash)
        }
    }

    fun get(
        document: Document,
        parameters: InstrumentationExecutionParameters
    ): Optional<QuerySignature>

    data class QuerySignature(val value: String, val hash: String)
}
