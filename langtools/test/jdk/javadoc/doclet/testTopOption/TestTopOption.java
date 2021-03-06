/*
 * Copyright (c) 2005, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug      6227616 8043186
 * @summary  Test the new -top option.
 * @author   jamieh
 * @library  ../lib
 * @modules jdk.javadoc/jdk.javadoc.internal.tool
 * @build    JavadocTester
 * @run main TestTopOption
 */

public class TestTopOption extends JavadocTester {

    public static void main(String... args) throws Exception {
        TestTopOption tester = new TestTopOption();
        tester.runTests();
    }

    @Test
    void test() {
        javadoc("-overview", testSrc("overview.html"),
                "-use",
                "-top", "TOP TEXT",
                "-d", "out-1",
                "-sourcepath", testSrc,
                "pkg");
        checkExit(Exit.OK);

        checkTopText(
                "pkg/AnnotationType.html",
                "pkg/class-use/AnnotationType.html",
                "pkg/Cl.html",
                "pkg/class-use/Cl.html",
                "pkg/package-summary.html",
                "pkg/package-use.html",
                "overview-summary.html",
                "overview-tree.html",
                "constant-values.html",
                "help-doc.html");
    }

    @Test
    void testDocRootRewrite() {
        javadoc("-overview", testSrc("overview.html"),
                "-use",
                "-top", "\u0130{@docroot}TOP TEXT",
                "-d", "out-2",
                "-sourcepath", testSrc,
                "pkg");
        checkExit(Exit.OK);

        checkTopText(
                "pkg/AnnotationType.html",
                "pkg/class-use/AnnotationType.html",
                "pkg/Cl.html",
                "pkg/class-use/Cl.html",
                "pkg/package-summary.html",
                "pkg/package-use.html",
                "overview-summary.html",
                "overview-tree.html",
                "constant-values.html",
                "help-doc.html");
    }

    void checkTopText(String... files) {
        for (String file : files) {
            checkOutput(file, true, "TOP TEXT");
        }
    }
}
