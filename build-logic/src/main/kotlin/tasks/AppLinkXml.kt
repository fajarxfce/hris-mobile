package tasks

import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

private const val ANDROID = "http://schemas.android.com/apk/res/android"

internal fun appLinkManifest(source: String, registration: AppLinkRegistration?): String {
    if (registration == null) return source
    val document = appLinkXml(source)
    val activities = document.getElementsByTagName("activity")
    val launchers =
        (0 until activities.length)
            .map { activities.item(it) as Element }
            .filter { activity ->
                activity.children("intent-filter").any { filter ->
                    filter.children("action").any {
                        it.getAttributeNS(ANDROID, "name") == "android.intent.action.MAIN"
                    } &&
                        filter.children("category").any {
                            it.getAttributeNS(ANDROID, "name") == "android.intent.category.LAUNCHER"
                        }
                }
            }
    require(launchers.size == 1) { "App links require one unambiguous launcher activity." }
    val filter =
        document.createElement("intent-filter").apply {
            setAttributeNS(ANDROID, "android:autoVerify", "true")
        }
    fun child(tag: String, vararg attributes: Pair<String, String>) {
        filter.appendChild(
            document.createElement(tag).apply {
                attributes.forEach { (name, value) ->
                    setAttributeNS(ANDROID, "android:$name", value)
                }
            }
        )
    }
    child("action", "name" to "android.intent.action.VIEW")
    child("category", "name" to "android.intent.category.DEFAULT")
    child("category", "name" to "android.intent.category.BROWSABLE")
    child(
        "data",
        "scheme" to "https",
        "host" to registration.host,
        "pathPrefix" to registration.pathPrefix,
    )
    launchers.single().appendChild(filter)
    return document.render()
}

internal fun appLinkEntitlements(source: String, registration: AppLinkRegistration?): String {
    val document = appLinkXml(source)
    require(document.documentElement.tagName == "plist") {
        "Apple base entitlements must be a plist."
    }
    val dictionary = document.documentElement.children("dict").single()
    require(
        dictionary.children("key").none {
            it.textContent == "com.apple.developer.associated-domains"
        }
    ) {
        "The app-link generator owns associated domains; remove duplicate base configuration."
    }
    if (registration?.appleAppId != null) {
        dictionary.appendChild(
            document.createElement("key").apply {
                textContent = "com.apple.developer.associated-domains"
            }
        )
        dictionary.appendChild(
            document.createElement("array").apply {
                appendChild(
                    document.createElement("string").apply {
                        textContent = "applinks:${registration.host}"
                    }
                )
            }
        )
    }
    return document.render()
}

private fun Element.children(tag: String): List<Element> =
    (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .filter { it.tagName == tag }

private fun appLinkXml(source: String): Document {
    val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
    return factory.newDocumentBuilder().parse(InputSource(StringReader(source)))
}

private fun Document.render(): String {
    val transformer =
        TransformerFactory.newInstance()
            .apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
            }
            .newTransformer()
    return StringWriter()
        .also { transformer.transform(DOMSource(this), StreamResult(it)) }
        .toString()
}
