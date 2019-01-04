//
// Copyright (C) 2018 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.sshd.SshCommand;
import com.google.gerrit.sshd.CommandMetaData;

@CommandMetaData(name="ping", description="Ping the SSH Command interface")
class QtCommandPing extends SshCommand {
    @Override
    protected void run() {
        stdout.print(String.format("Pong\n  username=%s\n  name=%s\n  email=%s\n",
                                    user.asIdentifiedUser().getUserName(),
                                    user.asIdentifiedUser().getName(),
                                    user.asIdentifiedUser().getNameEmail()));
    }
}

