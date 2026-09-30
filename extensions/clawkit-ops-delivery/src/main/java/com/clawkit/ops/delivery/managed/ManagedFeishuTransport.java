package com.clawkit.ops.delivery.managed;

import com.clawkit.im.feishu.FeishuApi;
import com.clawkit.im.feishu.FeishuApiException;
import com.clawkit.ops.loop.managed.ManagedLifecycleNotifier;

/** Existing bot API adapter; recipient and credentials come only from explicit product configuration. */
final class ManagedFeishuTransport implements ManagedLifecycleNotifier.Sender,AutoCloseable {
    private final FeishuApi api;
    ManagedFeishuTransport(String appId,String secret) { api=new FeishuApi(appId,secret); }
    @Override public String send(String chatId,String text,String key) throws Exception {
        try { return api.sendChatMessage(chatId,text,key); }
        catch (FeishuApiException e) { throw new ManagedLifecycleNotifier.SendFailure(e.retryable()); }
    }
    @Override public void close() { api.close(); }
}
