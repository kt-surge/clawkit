package com.clawkit.cli.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteIntentRouterTest {

    @TempDir Path tempDir;

    // ── PRODUCT-2: 常见中文服务器说法正确识别 ────────────────────────

    @Test
    void commonChineseStatusPhrasesAllMapToStatusIntent() {
        var router = new RemoteIntentRouter(
            new FileRemoteTargetStore(tempDir.resolve("targets.yaml")));

        // 查看状况 / 查看状态
        assertThat(router.resolve("查看状况").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);
        assertThat(router.resolve("查看状态").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);

        // 看看服务器 / 查看服务器 / 检查服务器
        assertThat(router.resolve("看看服务器").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);
        assertThat(router.resolve("查看服务器").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);
        assertThat(router.resolve("检查服务器").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);

        // 服务器情况 / 服务器状态 / 服务器连接情况
        assertThat(router.resolve("服务器情况").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);
        assertThat(router.resolve("服务器状态").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);
        assertThat(router.resolve("服务器连接情况").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);

        // 看一下服务器连接情况
        assertThat(router.resolve("看一下服务器连接情况").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);

        // 现在连接了哪台服务器
        assertThat(router.resolve("现在连接了哪台服务器").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);

        // 连接状态
        assertThat(router.resolve("查看连接状态。").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);
        assertThat(router.resolve("连接状态").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);
    }

    // ── PRODUCT-2: 非服务器请求不被错误识别 ──────────────────────────

    @Test
    void nonServerStatusRequestsStillGoToNormalConversation() {
        var router = new RemoteIntentRouter(
            new FileRemoteTargetStore(tempDir.resolve("targets.yaml")));

        assertThat(router.resolve("查看项目状况").intent())
            .isEqualTo(RemoteIntentRouter.Intent.NONE);
        assertThat(router.resolve("查看 Git 状态").intent())
            .isEqualTo(RemoteIntentRouter.Intent.NONE);
        assertThat(router.resolve("查看订单服务状态").intent())
            .isEqualTo(RemoteIntentRouter.Intent.NONE);
        assertThat(router.resolve("查看 README").intent())
            .isEqualTo(RemoteIntentRouter.Intent.NONE);
    }

    // ── 裸动词不应匹配 ─────────────────────────────────────────────

    @Test
    void bareVerbsDoNotMatch() {
        var router = new RemoteIntentRouter(
            new FileRemoteTargetStore(tempDir.resolve("targets.yaml")));

        assertThat(router.resolve("查看").intent())
            .isEqualTo(RemoteIntentRouter.Intent.NONE);
        assertThat(router.resolve("检查").intent())
            .isEqualTo(RemoteIntentRouter.Intent.NONE);
        assertThat(router.resolve("看看").intent())
            .isEqualTo(RemoteIntentRouter.Intent.NONE);
    }

    // ── 原有测试保留 ──────────────────────────────────────────────

    @Test
    void commonChineseStatusPhrasesUseRemoteStatusPath() {
        var router = new RemoteIntentRouter(
            new FileRemoteTargetStore(tempDir.resolve("targets.yaml")));

        assertThat(router.resolve("查看状况").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);
        assertThat(router.resolve("看一下服务器连接情况").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);
        assertThat(router.resolve("服务器连接情况").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);
        assertThat(router.resolve("查看连接状态。").intent())
            .isEqualTo(RemoteIntentRouter.Intent.STATUS);
    }

    @Test
    void unrelatedStatusRequestStillGoesToNormalConversation() {
        var router = new RemoteIntentRouter(
            new FileRemoteTargetStore(tempDir.resolve("targets.yaml")));

        assertThat(router.resolve("查看项目状况").intent())
            .isEqualTo(RemoteIntentRouter.Intent.NONE);
        assertThat(router.resolve("查看订单服务状态").intent())
            .isEqualTo(RemoteIntentRouter.Intent.NONE);
    }
}
