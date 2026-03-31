//
// Copyright (C) 2020-26 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.account.AccountResource.ACCOUNT_KIND;
import static com.google.gerrit.server.change.ChangeResource.CHANGE_KIND;
import static com.google.gerrit.server.change.RevisionResource.REVISION_KIND;
import static com.google.gerrit.server.group.GroupResource.GROUP_KIND;
import static com.googlesource.gerrit.plugins.qtcodereview.QtCiStatusUpdateCapability.CI_STATUS_UPDATE;
import static com.googlesource.gerrit.plugins.qtcodereview.QtStagingPromoteCapability.STAGING_PROMOTE;

import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.config.CapabilityDefinition;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.extensions.webui.TopMenu;
import com.google.gerrit.server.config.ProjectConfigEntry;
import com.google.gerrit.server.events.EventTypes;
import com.google.gerrit.server.git.ChangeMessageModifier;
import com.google.gerrit.server.mail.send.MailSoyTemplateProvider;

public class QtModule extends FactoryModule {

  static {
    EventTypes.register(QtChangeStagedEvent.TYPE, QtChangeStagedEvent.class);
    EventTypes.register(QtChangeUnStagedEvent.TYPE, QtChangeUnStagedEvent.class);
    EventTypes.register(QtChangePreCheckEvent.TYPE, QtChangePreCheckEvent.class);
  }

  @Override
  protected void configure() {

    // Plugin settings
    bind(CapabilityDefinition.class)
        .annotatedWith(Exports.named(CI_STATUS_UPDATE))
        .to(QtCiStatusUpdateCapability.class);
    bind(CapabilityDefinition.class)
        .annotatedWith(Exports.named(STAGING_PROMOTE))
        .to(QtStagingPromoteCapability.class);
    bind(ProjectConfigEntry.class)
        .annotatedWith(Exports.named("showReviewedOnFooter"))
        .toInstance(new ProjectConfigEntry("Show 'Reviewed-on' footer in commit messages", false));
    bind(ProjectConfigEntry.class)
        .annotatedWith(Exports.named("stagingQueueBranches"))
        .toInstance(
            new ProjectConfigEntry(
                "Branches in staging-queue mode (space-separated branch names)", ""));

    factory(QtChangeUpdateOp.Factory.class);
    DynamicSet.bind(binder(), ChangeMessageModifier.class).to(QtChangeMessageModifier.class);
    DynamicSet.bind(binder(), MailSoyTemplateProvider.class)
        .to(QtBuildFailedEmailTemplateRegister.class);
    DynamicSet.bind(binder(), TopMenu.class).to(QtCiStatusTopMenu.class);

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
            get(ACCOUNT_KIND, "cistatus").to(QtGetCiStatus.class);
            post(GROUP_KIND, "members.add").to(QtAddMembers.class);
          }
        });
  }
}
