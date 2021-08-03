package com.expediagroup.gateway

import com.example.schema.FederatedGatewaySchemaHooks
import com.expediagroup.gateway.config.FederatedConfig
import com.expediagroup.gateway.config.FederatedSchemaConfig
import com.expediagroup.gateway.schema.GatewaySchema
import graphql.GraphQL
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class GraphQLGateway {

    @Bean
    fun graphQL(): GraphQL {
        val config = FederatedConfig(
            services = listOf(
                FederatedSchemaConfig("helloService", "http://localhost:8080/graphql", "gateway/src/main/resources/helloSchema.graphql"),
                FederatedSchemaConfig("goodbyeService", "http://localhost:8081/graphql", "gateway/src/main/resources/goodbyeSchema.graphql")
            )
        )
        val schema = GatewaySchema(FederatedGatewaySchemaHooks()).generate(config)
        return GraphQL.newGraphQL(schema).build()
    }
}

fun main(args: Array<String>) {
    runApplication<GraphQLGateway>(*args)
}