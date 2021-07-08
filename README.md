# General

    This plugin extends standard the Gerrit functionality to support the review flow at codereview.qt-project.org.
    The main difference to the standard flow is that the final change merge is triggered by a CI backend. This is
    achieved by:
        * adding new statuses to the system: "staged" and "integrating"
        * adding SSH command APIs for the CI
        * adding REST APIs for status changes to be called from the Gerrit UI
        * modifying the UI to support this all
    There is also new status "deferred" which is similar to "abandoned".

    Below the status transition diagram:

    ____________            _______             __________               _______________                   __________
    | DEFERRED | <-defer--  | NEW | ---stage--> | STAGED | --new build-> | INTEGRATING | --build approve-> | MERGED |
        ^ |      --reopen->    ^    <-unstage--                              | |
        | |                    |                                             | |
        | |                    -------------------build reject---------------- |
        | V                    ^                                               |
    _____________              |                                               |
    | ABANDONED |              -------------------build  merge failed-----------

## UI Commands for a Change

    Submit to Staging
        * merges the change into the ref/staging/a_branch_name and updates the status to "staged".
    Unstage
        * updates change status back to "new" and rebuilds staging ref based on all changes in "staged" status.
    Defer
        * updates change status to "deferred".
    Reopen
        * updates change status back to new.

## SSH Command APIs

    Ping:
        * Just a ping API
        * example: ssh -p 29418 admin@codereview.qt-project.org gerrit-plugin-qt-workflow ping

    New Build
        * creates ref/builds/abuildname from current staging ref and the staged changes are moved to "integrating" status.
        * example: ssh -p 29418 anuser@codereview.qt-project.org gerrit-plugin-qt-workflow staging-new-build --staging-branch master --build-id b001 --project TestProject

    Build Approve
        * if result param is pass, merges build ref into target branch and the changes are moved to "merged" status.
        * if result param is fail, moves the changes in the build back to "new" status. Staging ref is rebuild to remove the failed changes.
        * example: ssh -p 29418 anuser@codereview.qt-project.org gerrit-plugin-qt-workflow staging-approve --branch master --build-id b001 --project TestProject --result pass

    Rebuild Staging
         * Staging ref is reseted to target branch and all changes in "Integrated" and "Staged" status are added to the staging ref.
         * example: ssh -p 29418 anuser@codereview.qt-project.org gerrit-plugin-qt-workflow staging-rebuild --branch master --project TestProject

    List Staging
         * List changes between a ref and the destination branch
         * example: ssh -p 29418 anuser@codereview.qt-project.org gerrit-plugin-qt-workflow staging-ls --branch refs/staging/master --destination master --project TestProject

## Development

    The plugin contains two parts:
        * qt-gerrit-ui-plugin.html extends the UI and it is a PolyGerrit plugin.
        * gerrit-plugin-qt-workflow.jar extends the REST and SSH command APIs. It's type is gerrit-plugin-api.jar.

    See:
        * https://gerrit-review.googlesource.com/Documentation/dev-plugins.html
        * https://gerrit-review.googlesource.com/Documentation/pg-plugin-dev.html
        * https://gerrit-review.googlesource.com/Documentation/dev-readme.html
        * https://gerrit-review.googlesource.com/Documentation/dev-bazel.html

    Building the UI Plugin
         * No need to build, it is all running on the client browser.

    Building the Java Plugin
        * have a gerrit checkout and clone the plugin to plugins/gerrit-plugin-qt-workflow (we should add it as submodule when it's done)
        * run "bazelisk build plugins/gerrit-plugin-qt-workflow"
        * the binary will be "bazel-genfiles/plugins/gerrit-plugin-qt-workflow/gerrit-plugin-qt-workflow.jar"

    Building Gerrit
        * run "bazelisk build release"
        * copy binary file to the gerrit site directory: "cp bazel-bin/release.war thedir/bin/gerrit.war"

    Working with the local development environment
        * Init test site
            https://gerrit-review.googlesource.com/Documentation/dev-readme.html#init
        * Set access righs for test projects:
            go: http://localhost:8080/admin/repos/All-Projects,access
            allow admin/test users to create reference: refs/*
        * Gerrit commands:
            ./bin/gerrit.sh start
            ./bin/gerrit.sh stop
            ./bin/gerrit.sh run
        * web access
            http://localhost:8080
        * SSH API command examples:
            ssh -p 29418 admin@localhost gerrit-plugin-qt-workflow ping
            ssh -p 29418 admin@localhost gerrit-plugin-qt-workflow staging-new-build --staging-branch master --build-id b001 --project JustTest
            ssh -p 29418 admin@localhost gerrit-plugin-qt-workflow staging-approve --branch master --build-id b001 --project JustTest --result pass
            ssh -p 29418 admin@localhost gerrit stream-events
            ssh -p 29418 admin@localhost gerrit index changes 17 26 27 28
        * Fetching staging and builds refs to work area.
            git fetch -f origin refs/staging/*:refs/staging/*
            git fetch -f origin refs/builds/*:refs/builds/*
        * Running tests:
            bazelisk test --test_output=streamed //plugins/gerrit-plugin-qt-workflow:*
        * Running a single test, for example:
            bazelisk test --test_output=streamed //plugins/gerrit-plugin-qt-workflow:qtcodereview_tests --test_filter=singleChange_Defer_QtAbandon
        * Test coverage:
            install genhtml tool, for example: brew install lcov
            run: bazelisk coverage //plugins/gerrit-plugin-qt-workflow:*
            genhtml -o ./coveragedir --ignore-errors source thegeneratedcoveragefile.dat
            => html report available at ./coveragedir/index.html

## Installation

    Copy gerrit-plugin-qt-workflow.jar and qt-gerrit-ui-plugin.html to the gerrit site "plugins" directory.
    NOTE that the plugin depends on the Gerrit internal APIs, so if Gerrit is upgraded to a new version the plugin
    needs to be recompiled against the same version.

    As of today the plugin requires custom patches on top of the Gerrit. So the Gerrit binary needs to be
    rebuild with the patches. The plan is to upstream these changes.

    Access rights:
       * Defer/Reopen requires same permissions as abandon
       * CI needs the permission to created references (refs/builds/*), push to branches (refs/heads/*) and
         force push to staging refs (refs/staging/*)

    Copy static files into the site dir: cp gerrit-plugin-qt-workflow/static/* gerritsitedir/static/
