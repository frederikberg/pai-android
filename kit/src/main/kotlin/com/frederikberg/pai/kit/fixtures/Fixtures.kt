package com.frederikberg.pai.kit.fixtures

/**
 * Canned answers for [FixtureInterceptor]; grows with the screens that need them. Keys carry the
 * `/api` prefix the request factory adds, because the interceptor sees the request as it goes out.
 * All names and text are invented.
 */
object Fixtures {
    const val SESSION_HOME = "00000000-0000-4000-8000-0000000000a1"
    const val SESSION_RESEARCH = "00000000-0000-4000-8000-0000000000a2"

    private fun session(
        id: String,
        title: String,
        displayState: String,
        activity: String,
    ) = """
        {"id":"$id","session_type":"home","status":"active","state":"ready","display_state":"$displayState",
         "title":"$title","initial_message":"$title","session_tokens":18400,"created_at":"2026-11-01T08:00:00+00:00",
         "updated_at":"$activity","last_activity_at":"$activity","working_dir":"/home/guest","kind":"conversation"}
        """.trimIndent()

    val routes: Map<String, String> =
        mapOf(
            "GET /api/me" to
                """{"identity":"alex@example.com","role":"guest","user_id":"00000000-0000-4000-8000-0000000000e1",
                   "display_name":"Alex Example","allowed_session_ids":[],
                   "namespaces":[{"slug":"example","display_name":"Example household"}]}""",
            "GET /api/sessions" to
                "[${session(SESSION_HOME, "Plan the garden", "working", "2026-11-20T09:30:00+00:00")}," +
                "${session(SESSION_RESEARCH, "Compare train tickets", "done", "2026-11-19T17:10:00+00:00")}]",
            "GET /api/notifications/summary" to """{"unread":2}""",
            "GET /api/usage" to
                """{"five_hour":{"utilization":23.0,"resets_at":"2026-11-20T14:00:00+00:00"},
                   "seven_day":{"utilization":41.0,"resets_at":"2026-11-25T09:00:00+00:00"}}""",
        )
}
