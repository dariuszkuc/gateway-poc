package com.expediagroup.gateway.schema.scalars

import graphql.schema.Coercing

object CustomScalarCoercing : Coercing<Any, String> {
    override fun serialize(dataFetcherResult: Any): String  = dataFetcherResult.toString()

    override fun parseValue(input: Any): Any = input.toString()

    override fun parseLiteral(input: Any): Any = input
}