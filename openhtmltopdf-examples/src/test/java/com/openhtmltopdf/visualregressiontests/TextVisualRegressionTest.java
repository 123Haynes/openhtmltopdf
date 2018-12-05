package com.openhtmltopdf.visualregressiontests;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.Assert.assertTrue;

import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.openhtmltopdf.extend.FSTextBreaker;
import com.openhtmltopdf.visualtest.VisualTester;
import com.openhtmltopdf.visualtest.VisualTester.BuilderConfig;

public class TextVisualRegressionTest {
    private VisualTester vt;
    
    /**
     * A simple line breaker so that our tests are not reliant on the external Java API.
     */
    private static class SimpleTextBreaker implements FSTextBreaker {
        private String text;
        private int position;
        
        @Override
        public int next() {
            int ret = text.indexOf(' ', this.position);
            this.position = ret + 1;
            return ret;
        }

        @Override
        public void setText(String newText) {
            this.text = newText;
            this.position = 0;
        }
    }
    
    private static final BuilderConfig WITH_FONT = (builder) -> {
        builder.useFont(new File("target/test/visual-tests/Karla-Bold.ttf"), "TestFont");
        builder.useUnicodeLineBreaker(new SimpleTextBreaker());
    };
    
    /**
     * Output the font file as a regular file so we don't have to use streams.
     * @throws IOException
     */
    @BeforeClass
    public static void makeFontFile() throws IOException {
        File overrideDirectory = new File("target/test/visual-tests/user-override/");
        File outputDirectory = new File("target/test/visual-tests/test-output/");
        
        overrideDirectory.mkdirs();
        outputDirectory.mkdirs();
        
        File fontFile = new File("target/test/visual-tests/Karla-Bold.ttf");
        
        if (!fontFile.exists()) {
            try (InputStream in = TextVisualRegressionTest.class.getResourceAsStream("/visualtest/html/fonts/Karla-Bold.ttf");
                 OutputStream out = new FileOutputStream("target/test/visual-tests/Karla-Bold.ttf")) {
                 IOUtils.copy(in, out);
            }
        }
    }
    
    @Before
    public void configureTester() {
        File overrideDirectory = new File("target/test/visual-tests/user-override/");
        File outputDirectory = new File("target/test/visual-tests/test-output/");
        
        vt = new VisualTester("/visualtest/html/text/", /* Resource path. */
                new File("src/main/resources/visualtest/expected/text/"), /* Expected directory */
                overrideDirectory,
                outputDirectory
                );
    }
    
    private boolean run(String resource) throws IOException {
        return vt.runTest(resource, WITH_FONT);
    }
    
    /**
     * Tests simple text output in absolute positioned blocks.
     */
    @Test
    public void testPositioningAbsolute() throws IOException {
        assertTrue(run("positioning-absolute"));
    }
}
