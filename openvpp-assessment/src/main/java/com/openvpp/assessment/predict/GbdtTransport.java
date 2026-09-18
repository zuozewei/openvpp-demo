package com.openvpp.assessment.predict;

import java.io.IOException;

/**
 * GBDT 推理副车传输接口 —— 专栏第 47 篇。
 *
 * 抽出接口是为了可测：单测注入桩实现，不依赖真实 Python 进程；
 * 生产实现是 {@link HttpGbdtTransport}（127.0.0.1 副车，只绑回环不进局域网）。
 */
public interface GbdtTransport {

    /** GET（/health）。失败抛 IOException。 */
    String get(String path) throws IOException;

    /** POST JSON（/predict、/predict_load）。失败抛 IOException。 */
    String post(String path, String jsonBody) throws IOException;
}
