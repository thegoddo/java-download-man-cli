package com.downloader.storage;

import java.io.FileOutputStream;
import java.io.OutputStream;

public class AppendDemo {
    public static void main(String[] args) throws Exception{
        String hello = "Hello ";
        OutputStream outputStream = new FileOutputStream("sample.txt", true);
        byte[] data = hello.getBytes();

        outputStream.write(data);
        outputStream.close();

        hello = "World!!";
        data = hello.getBytes();
        OutputStream outputStream2 = new FileOutputStream("sample.txt", true);
        outputStream2.write(data);
        outputStream2.close();

    }
}
