package com.expediagroup.gateway.config

data class FederatedSchemaConfig(
    val serviceName: String,
    val endpoint: String,
    val schemaFile: String // TODO make this optional - use introspection or SDL endpoint instead
)