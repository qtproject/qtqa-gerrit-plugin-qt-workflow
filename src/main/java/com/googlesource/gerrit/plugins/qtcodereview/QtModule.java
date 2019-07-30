//
// Copyright (C) 2019 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.change.ChangeResource.CHANGE_KIND;
import static com.google.gerrit.server.change.RevisionResource.REVISION_KIND;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.server.git.ChangeMessageModifier;

public class QtModule extends FactoryModule {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Override
  protected void configure() {

    factory(QtBuildFailedSender.Factory.class);
    factory(QtChangeUpdateOp.Factory.class);
    DynamicSet.bind(binder(), ChangeMessageModifier.class).to(QtChangeMessageModifier.class);

    install(
        new RestApiModule() {
          @Override
          protected void configure() {
            post(CHANGE_KIND, "abandon").to(QtAbandon.class);
            post(CHANGE_KIND, "defer").to(QtDefer.class);
            post(CHANGE_KIND, "reopen").to(QtReOpen.class);
            post(REVISION_KIND, "stage").to(QtStage.class);
            post(REVISION_KIND, "unstage").to(QtUnStage.class);
          }
        });
  }
}
