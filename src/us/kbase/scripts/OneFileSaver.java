package us.kbase.scripts;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;

public class OneFileSaver implements FileSaver {
    private final FileSaver folder;
    private final String relPath;
    
    public OneFileSaver(FileSaver folder, String relPath) {
        this.folder = folder;
        this.relPath = relPath;
    }
    
    @Override
    public File getAsFileOrNull(String path) throws IOException {
        return null;
    }
    
    @Override
    public Writer openWriter(String path) throws IOException {
        return folder.openWriter(relPath);
    }
    
    @Override
    public OutputStream openStream(String path) throws IOException {
        return folder.openStream(relPath);
    }
}
