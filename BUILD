load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "PLUGIN_DEPS", "PLUGIN_TEST_DEPS", "gerrit_plugin")

gerrit_plugin(
    name = "gerrit-plugin-qt-workflow",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: gerrit-plugin-qt-workflow",
        "Gerrit-Module: com.googlesource.gerrit.plugins.qtcodereview.QtModule",
        "Gerrit-SshModule: com.googlesource.gerrit.plugins.qtcodereview.QtSshModule",
        "Implementation-Title: Qt Code Review Flow Plugin",
        "Implementation-URL: https://codereview.qt-project.org/p/qtqa/gerrit-plugin-qt-workflow.git",
    ],
    resources = glob(["src/main/resources/**/*"]),
)

junit_tests(
    name = "qtcodereview_tests",
    size = "large",
    srcs = glob(["src/test/java/**/*IT.java"]),
    tags = ["qtcodereview"],
    visibility = ["//visibility:public"],
    deps = PLUGIN_TEST_DEPS + PLUGIN_DEPS + [
        ":gerrit-plugin-qt-workflow__plugin",
    ],
)
