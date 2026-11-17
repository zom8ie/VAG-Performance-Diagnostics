package ru.yeti.diag;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only content provider for sharing an exported daily journal. */
public final class ShareProvider extends ContentProvider {
    private File fileFor(Uri uri) throws IOException {
        if(getContext()==null||!"shared".equals(uri.getPathSegments().isEmpty()?"":uri.getPathSegments().get(0)))throw new FileNotFoundException("Недопустимый путь");
        File root=new File(getContext().getCacheDir(),"shared").getCanonicalFile();
        String name=Uri.decode(uri.getPathSegments().get(1));File file=new File(root,name).getCanonicalFile();
        if(!file.getPath().startsWith(root.getPath()+File.separator))throw new FileNotFoundException("Недопустимый файл");
        return file;
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return "application/x-ndjson";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Только чтение");
        try{return ParcelFileDescriptor.open(fileFor(uri),ParcelFileDescriptor.MODE_READ_ONLY);}catch(IOException e){throw new FileNotFoundException(e.getMessage());}
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
        try{File f=fileFor(uri);MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{f.getName(),f.length()});return c;}catch(IOException e){return null;}
    }
    @Override public int delete(Uri uri,String selection,String[] selectionArgs){return 0;}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){return 0;}
    @Override public Uri insert(Uri uri,ContentValues values){return null;}
}
