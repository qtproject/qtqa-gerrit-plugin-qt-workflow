//
// Copyright (C) 2018 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.change.ChangeResource.CHANGE_KIND;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.extensions.restapi.RestApiModule;

import com.google.inject.Inject;
import com.google.inject.AbstractModule;

public class QtModule extends FactoryModule  {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    @Override
    protected void configure() {

        factory(QtChangeUpdateOp.Factory.class);

        install(
            new RestApiModule() {
                @Override
                protected void configure() {
                    post(CHANGE_KIND, "defer").to(QtDefer.class);
                }
            }
        );

    }

}
