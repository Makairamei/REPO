package com.putarflix

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object PutarFlixParser {
    fun itemNodes(doc: Document): List<Element> {
        return doc.select("article, .item, .result-item, .bs, .ml-item")
    }

    fun titleFrom(element: Element): String {
        val anchor = element.selectFirst("a[href]")
        return PutarFlixUtils.cleanTitle(
            element.selectFirst(".entry-title, .tt, .title, h2, h3")?.text()
                ?: anchor?.attr("title")
                ?: anchor?.text()
                ?: ""
        )
    }
}
