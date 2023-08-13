/*
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2023. Georg Beier. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.geobe.energy.web

import io.pebbletemplates.pebble.PebbleEngine

class GraphController {

    def createGraphCtx(int size, int lineCount) {
        def labels = []
        def datasets = []
        def lines = []

        for (i in 0..<size) {
            labels << i
        }
        for (j in 0..<lineCount) {
            def dataset = []
            for (k in 0..<size) {
                dataset << (int) (8000.0 * Math.random() - 3000.0)
            }
            datasets << dataset
            def colorKey = Colors.w3Color.keySet().asList()[j * 3]
            lines << [
                    label: 'line' + j,
                    color: Colors.w3Color[colorKey]
            ]
        }
        def ctx = [
                graphTitle: 'Chart Grafik',
                labels    : labels.toString(),
                datasets  : datasets,
                lines     : lines
        ]
    }

    static void main(String[] args) {
        def gc = new GraphController()
        def ctx = gc.createGraphCtx(20, 3)
        def engine = new PebbleEngine.Builder().build()
        def template = engine.getTemplate('template/graphdata.peb')
        def out = new StringWriter()
        template.evaluate(out, ctx)
        println out

    }
}
