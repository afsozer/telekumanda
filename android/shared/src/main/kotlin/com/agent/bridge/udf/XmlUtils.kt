package com.agent.bridge.udf

import javax.xml.parsers.DocumentBuilderFactory

fun secureDocumentBuilderFactory(): DocumentBuilderFactory {
    val dbFactory = DocumentBuilderFactory.newInstance()
    dbFactory.isCoalescing = true
    try {
        dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    } catch (e: Exception) {
        // Not supported
    }
    try {
        dbFactory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    } catch (e: Exception) {
        // Not supported
    }
    try {
        dbFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    } catch (e: Exception) {
        // Not supported
    }
    try {
        dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    } catch (e: Exception) {
        // Not supported
    }
    try {
        dbFactory.isXIncludeAware = false
    } catch (e: Exception) {
        // Not supported on Android
    }
    try {
        dbFactory.isExpandEntityReferences = false
    } catch (e: Exception) {
        // Not supported
    }
    return dbFactory
}

fun getElementText(element: org.w3c.dom.Element): String {
    val text = element.textContent
    if (!text.isNullOrEmpty()) {
        return text
    }
    val sb = StringBuilder()
    val childNodes = element.childNodes
    for (i in 0 until childNodes.length) {
        val child = childNodes.item(i)
        val nodeType = child.nodeType
        if (nodeType == org.w3c.dom.Node.TEXT_NODE || nodeType == org.w3c.dom.Node.CDATA_SECTION_NODE) {
            sb.append(child.nodeValue ?: "")
        }
    }
    return sb.toString()
}


