//
// Copyright (C) 2018 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.extensions.restapi.RestApiModule;

import com.google.inject.Inject;
import com.google.inject.AbstractModule;

public class QtModule extends FactoryModule  {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    @Override
    protected void configure() {

        install(
            new RestApiModule() {
                @Override
                protected void configure() {
                }
            }
        );

    }

}
