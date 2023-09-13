/*
 * Copyright (c) 2023. Georg Beier. All rights reserved.
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package de.geobe.energy.web

class UiStringsI18n {

    List<String > translations
    String[] translationIndex
    Map translationStrings = [:]
    Map translationsMap = [:]

    UiStringsI18n(String filename = '/i18n/UiStringsI18n.csv') {
        readFile(filename)
    }

    def readFile(String filename) {
        def r = this.getClass().getResource(filename)
        translations = r.readLines()
        translationIndex = translations[0].split(':')
        def group = [:]
        String key = ''
        for (i in 1..<translations.size()) {
            def line = translations[i]
            if (line.startsWith('* ')) {
                if (key && group.size() > 0) {
                    translationStrings.put(key, group)
                }
                line = line.replaceAll(':', '')
                key = line.substring(2)
                group = [:]
            } else if (line.startsWith('/')) {
                // skip comments
            } else {
            def entry = line.split(':')
                group.put(entry.head(), entry)
            }
        }
        // put last group into translations
        if (key && group.size() > 0) {
            translationStrings.put(key, group)
        }
    }

    def i18nCtx(Map<String, Map<String, String>> ti18n, String language) {
        StringBuffer options = new StringBuffer()
        translationIndex.each {key ->
            def val = ti18n.languages[key]
            if (val ) {
                options.append "<option value='$key'${key == language?' selected':''}>$val</option>\n".toString()
            }
        }
        [languageOptions:options.toString()]
    }

    def i18nSelect(Map<String, Map<String, String>> ti18n, String language) {
"""
                <select  class="w3-button w3-right w3-margin-right" name="language"
                hx-post="/language"
                hx-target="#language-select" hx-swap="innerHtml">
                    ${i18nCtx(ti18n, language).languageOptions}
                <select>
"""
    }

    Map<String, Map<String, String>> translationsFor(String language) {
        if (! translationsMap[language]) {
            def index = translationIndex.findIndexOf { it.equalsIgnoreCase(language) }
            index = index < 0 ? 0 : index
            Map<String, Map<String, String>> tx = [:]
            translationStrings.each { txEntry ->
                Map txGroup = txEntry.value
                def txLocalized = [:]
                txGroup.each { locEntry ->
                    txLocalized.put(locEntry.key, locEntry.value[index] ?: locEntry.value[0])
                }
                tx.put(txEntry.key, txLocalized)
            }
            translationsMap.put(language, tx)
        }
        translationsMap[language]
    }

    static void main(String[] args) {
        def st = new UiStringsI18n()
//        st.readFile()
        def tx = st.translationsFor('de')
        println st.i18nCtx(tx)
    }

}
