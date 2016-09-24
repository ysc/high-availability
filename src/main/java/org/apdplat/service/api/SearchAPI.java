package org.apdplat.service.api;

/**
 * Created by ysc on 7/5/16.
 */
public interface SearchAPI {
    String search(String keyword, int topN);
    String getStatus();
    void close();
}
