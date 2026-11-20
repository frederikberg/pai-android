package com.frederikberg.pai.contract

import kotlinx.serialization.json.Json

/**
 * The one decoder configuration for every PAI Cloud payload.
 *
 * Unknown keys are ignored so a server that grows a field does not break an installed app, and a
 * null or unknown value for a field with a default falls back to that default.
 */
val ContractJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
