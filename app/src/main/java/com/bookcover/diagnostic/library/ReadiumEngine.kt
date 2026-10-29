package com.bookcover.diagnostic.library

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.bookcover.diagnostic.reader.bookSearchServiceFactory
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.encryption.encryption
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.searchServiceFactory
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.http.HttpTry
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.map
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

@OptIn(ExperimentalReadiumApi::class)
internal class ReadiumEngine(context: Context) {
    private companion object {
        /** IDPF and Adobe font obfuscation: scrambles embedded fonts only, not DRM. */
        val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
    }

    // This prototype reads owned local copies. No network client or download integration is enabled.
    private val http = object : HttpClient {
        override suspend fun stream(request: HttpRequest): HttpTry<HttpStreamResponse> =
            Try.failure(HttpError.IO(IOException("Remote publication resources are disabled")))
    }
    private val retriever = AssetRetriever(context.contentResolver, http)
    private val opener = PublicationOpener(DefaultPublicationParser(context, httpClient = http, assetRetriever = retriever, pdfFactory = null))

    suspend fun open(file: File): Publication {
        val asset = retriever.retrieve(file.toUrl(isDirectory = false)).getOrElse { throw IOException("Could not read this EPUB file") }
        // Readium 3.4.0's PublicationOpener never calls the constructor's onCreatePublication and calls the
        // per-open one twice, so the markup filter is passed here and guarded to be installed once.
        var filtered = false
        val publication = opener.open(asset, allowUserInteraction = false, onCreatePublication = {
            if (!filtered) {
                filtered = true
                container = PublicationResources(container)
                val searchContainer = container
                installMarkupFilter()
                servicesBuilder.searchServiceFactory = bookSearchServiceFactory(searchContainer)
            }
        }).getOrElse { throw IOException("This file could not be opened as an EPUB") }
        try {
            require(publication.conformsTo(Publication.Profile.EPUB)) { "Please choose an EPUB book" }
            require(!publication.isRestricted) { "This EPUB is protected. The prototype opens DRM-free EPUBs." }
            // Readium only flags DRM schemes it has a protection service for. Any other encrypted chapter or
            // resource would display as scrambled text, so refuse it; font obfuscation is ordinary and allowed.
            require((publication.readingOrder + publication.resources).none { link ->
                link.properties.encryption?.let { it.algorithm !in FONT_OBFUSCATION } == true
            }) { "This EPUB is protected. The prototype opens DRM-free EPUBs." }
            require(publication.readingOrder.isNotEmpty()) { "This EPUB has no readable chapters" }
            publication.positions() // Populate the cached position service before constructing the navigator.
            currentCoroutineContext().ensureActive()
            return publication
        } catch (error: Throwable) {
            publication.close()
            throw error
        }
    }

    /** Serves every HTML/SVG resource through [passivePublicationMarkup]: scripts stripped, re-encoded as UTF-8. */
    private fun Publication.Builder.installMarkupFilter() {
        val markupPaths = (manifest.readingOrder + manifest.resources).filter {
            val type = it.mediaType?.toString().orEmpty()
            "html" in type || "svg" in type
        }.map { it.url().toString() }.toSet()
        container = TransformingContainer(container) { url, resource ->
            val path = url.toString().substringBefore('?').substringBefore('#')
            if (url.toString() in markupPaths || listOf(".html", ".xhtml", ".htm", ".svg").any { path.endsWith(it, true) }) {
                resource.map { Try.success(passivePublicationMarkup(it)) }
            } else resource
        }
    }
}
