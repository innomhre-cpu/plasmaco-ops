@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.springframework.web.servlet.config.annotation

import org.springframework.http.converter.HttpMessageConverter
interface WebMvcConfigurer {
    fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {}
    fun configureMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {}
}
