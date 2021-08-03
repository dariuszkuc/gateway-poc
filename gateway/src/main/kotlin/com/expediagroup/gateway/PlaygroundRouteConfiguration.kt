/*
 * Copyright 2021 Expedia, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.expediagroup.gateway

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.reactive.function.server.html

/**
 * Configuration for exposing the GraphQL Playground on a specific HTTP path
 */
@Configuration
class PlaygroundRouteConfiguration(
    @Value("classpath:/graphql-playground.html") private val playgroundHtml: Resource,
    @Value("\${spring.webflux.base-path:#{null}}") private val contextPath: String?
) {

    private val body = playgroundHtml.inputStream.bufferedReader().use { reader ->
        val graphQLEndpoint = if (contextPath.isNullOrBlank()) "graphql" else "$contextPath/graphql"
        val subscriptionsEndpoint = if (contextPath.isNullOrBlank()) "graphql" else "$contextPath/graphql"

        reader.readText()
            .replace("\${graphQLEndpoint}", graphQLEndpoint)
            .replace("\${subscriptionsEndpoint}", subscriptionsEndpoint)
    }

    @Bean
    fun playgroundRoute() = coRouter {
        GET("playground") {
            ok().html().bodyValueAndAwait(body)
        }
    }
}
