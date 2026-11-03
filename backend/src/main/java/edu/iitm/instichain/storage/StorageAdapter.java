package edu.iitm.instichain.storage;

import java.io.InputStream;

public interface StorageAdapter {
    String provider();
    FileMetadata metadata(String accessToken, String fileId);
    InputStream download(String accessToken, String fileId);

    record FileMetadata(String id, String name, String mimeType, long size) {}
}
