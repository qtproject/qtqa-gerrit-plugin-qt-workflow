//
// Copyright (C) 2020-22 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.change.ChangeResource.CHANGE_KIND;
import static com.google.gerrit.server.change.RevisionResource.REVISION_KIND;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.server.git.ChangeMessageModifier;
import com.google.gerrit.server.events.EventTypes;
import com.google.gerrit.server.mail.send.MailSoyTemplateProvider;

public class QtModule extends FactoryModule {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static {
    EventTypes.register(QtChangeStagedEvent.TYPE, QtChangeStagedEvent.class);
    EventTypes.register(QtChangeUnStagedEvent.TYPE, QtChangeUnStagedEvent.class);
    EventTypes.register(QtChangePreCheckEvent.TYPE, QtChangePreCheckEvent.class);
  }

  @Override
  protected void configure() {

    factory(QtBuildFailedSender.Factory.class);
    factory(QtChangeUpdateOp.Factory.class);
    DynamicSet.bind(binder(), ChangeMessageModifier.class).to(QtChangeMessageModifier.class);
    DynamicSet.bind(binder(), MailSoyTemplateProvider.class).to(QtBuildFailedEmailTemplateRegister.class);

    install(
        new RestApiModule() {
          @Override
          protected void configure() {
            post(CHANGE_KIND, "abandon").to(QtAbandon.class);
            post(CHANGE_KIND, "defer").to(QtDefer.class);
            post(CHANGE_KIND, "reopen").to(QtReOpen.class);
            post(REVISION_KIND, "stage").to(QtStage.class);
            post(REVISION_KIND, "unstage").to(QtUnStage.class);
            post(REVISION_KIND, "precheck").to(QtPreCheck.class);
          }
        });
  }
}
