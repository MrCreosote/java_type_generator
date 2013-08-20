package us.kbase.scripts.tests.test10;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import junit.framework.Assert;

import us.kbase.scripts.tests.test10.commentstest.CommentstestClient;
import us.kbase.scripts.tests.test10.commentstest.CommentstestServer;
import us.kbase.scripts.tests.test10.commentstest.FileType;

public class Test10 {

    public Test10(File rootDir) throws Exception {
    	checkFileContains(rootDir, CommentstestClient.class, "KBase File Type Manager Service", "Returns the specified file_type object",
    			"Original type \"file_type_id_ref\" (Reference to file type which is necessary for complicated network of cross-references) " +
    			"&rarr; Original type \"file_type_id\" (The unique ID of a file type, which cannot contain any spaces (e.g. file, text, html))");
    	checkFileContains(rootDir, CommentstestServer.class, "Original type \"file_type_ref2\" (This reference certainly should reflect some purpose " +
    			"of developer.) &rarr; Original type \"file_type_ref\" &rarr; Original type \"file_type\" (see", 
    			"Original type \"my_tuple\" (Testing tuple comment)");
    	checkFileContains(rootDir, FileType.class, "An object that encapsulates properties of a file type.");
    }
    
    private static void checkFileContains(File rootDir, Class<?> classRef, String... words) throws Exception {
    	File f = new File(rootDir, "doc/" + classRef.getName().replace('.', '/') + ".html");
    	BufferedReader br = new BufferedReader(new FileReader(f));
    	StringBuilder sb = new StringBuilder();
    	while (true) {
    		String l = br.readLine();
    		if (l == null)
    			break;
    		sb.append(l).append('\n');
    	}
    	br.close();
    	String text = sb.toString();
    	for (String word : words) {
    		if (!text.contains(word))
    			Assert.fail("Documentation of " + classRef.getName() + " doesn't contain [" + word + "]");
    	}
    }
}