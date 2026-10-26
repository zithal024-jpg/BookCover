package com.bookcover.diagnostic.library

import android.content.Context
import java.io.File
import java.io.IOException
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.positions
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

internal class ReadiumEngine(context: Context) {
    // This prototype reads owned local copies. No network client or download integration is enabled.
    private val http = object : HttpClient {
        override suspend fun stream(request: HttpRequest): HttpTry<HttpStreamResponse> =
            Try.failure(HttpError.IO(IOException("Remote publication resources are disabled")))
    }
    private val retriever = AssetRetriever(context.contentResolver, http)
    private val opener = PublicationOpener(
        DefaultPublicationParser(context, httpClient = http, assetRetriever = retriever, pdfFactory = null),
        onCreatePublication = {
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
        },
    )

    suspend fun open(file: File): Publication {
        val asset = retriever.retrieve(file.toUrl(isDirectory = false)).getOrElse { throw IOException("Could not read this EPUB file") }
        val publication = opener.open(asset, allowUserInteraction = false)
            .getOrElse { throw IOException("This file could not be opened as an EPUB") }
        try {
            require(publication.conformsTo(Publication.Profile.EPUB)) { "Please choose an EPUB book" }
            require(!publication.isRestricted) { "This EPUB is protected. The prototype opens DRM-free EPUBs." }
            require(publication.readingOrder.isNotEmpty()) { "This EPUB has no readable chapters" }
            publication.positions() // Populate the cached position service before constructing the navigator.
            return publication
        } catch (error: Exception) {
            publication.close()
            throw error
        }
    }
}
