package com.frederikberg.pai.kit.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

/** A `multipart/form-data` body, built once: the bytes and the content type that carries their boundary. */
class MultipartForm private constructor(
    val bytes: ByteArray,
    val contentType: String,
) {
    class Builder {
        private val body = MultipartBody.Builder().setType(MultipartBody.FORM)

        fun field(
            name: String,
            value: String,
        ) = apply { body.addFormDataPart(name, value) }

        fun file(
            name: String,
            filename: String,
            contentType: String,
            data: ByteArray,
        ) = apply { body.addFormDataPart(name, filename, data.toRequestBody(contentType.toMediaType())) }

        fun build(): MultipartForm {
            val built = body.build()
            val buffer = Buffer()
            built.writeTo(buffer)
            return MultipartForm(buffer.readByteArray(), built.contentType().toString())
        }
    }
}
