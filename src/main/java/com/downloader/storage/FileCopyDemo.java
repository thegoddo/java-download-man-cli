package com.downloader.storage;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class FileCopyDemo {
    public static void main(String[] args) throws Exception {
        byte[] buffer = new byte[8192];
        int bytesRead;
        InputStream inputStream = new FileInputStream("sample.txt");
        OutputStream outputStream = new FileOutputStream("sample.md");

        while((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        inputStream.close();
        outputStream.close();
    }
}
