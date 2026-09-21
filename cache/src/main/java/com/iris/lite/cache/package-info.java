/*
 * iris-lite 缓存模块（端口层）。
 *
 * 内容：缓存仓储端口（CacheRepository）与文本向量化端口（Embedder）。
 *
 * 只定义接口，实现（Lettuce / Bm25Embedder）在 infrastructure 层——
 * 这样换缓存后端或换 embedding 模型时，application 层零改动。
 */
package com.iris.lite.cache;
