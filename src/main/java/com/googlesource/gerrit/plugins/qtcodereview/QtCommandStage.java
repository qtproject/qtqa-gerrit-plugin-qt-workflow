//
// Copyright (C) 2019 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.restapi.change.ChangesCollection;
import com.google.gerrit.server.restapi.change.Revisions;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.gerrit.sshd.commands.PatchSetParser;
import com.google.inject.Inject;
import java.util.HashSet;
import java.util.Set;
import org.kohsuke.args4j.Argument;

@CommandMetaData(name = "stage", description = "Stage a change.")
class QtCommandStage extends SshCommand {

  @Inject private QtStage qtStage;
  @Inject private ChangesCollection changes;
  @Inject private PatchSetParser psParser;
  @Inject private Revisions revisions;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Set<PatchSet> patchSets = new HashSet<>();

  @Argument(
      index = 0,
      required = true,
      multiValued = true,
      metaVar = "{COMMIT | CHANGE,PATCHSET}",
      usage = "list of commits or patch sets to stage")
  void addPatchSetId(String token) {
    try {
      PatchSet ps = psParser.parsePatchSet(token, null, null);
      patchSets.add(ps);
    } catch (UnloggedFailure e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  @Override
  protected void run() throws UnloggedFailure {
    boolean ok = true;

    for (PatchSet patchSet : patchSets) {
      try {
        logger.atInfo().log("qtcodereview: ssh command stage %s", patchSet.id());
        ChangeResource c = changes.parse(patchSet.id().changeId());
        IdString id = IdString.fromDecoded(patchSet.commitId().name());
        RevisionResource r = revisions.parse(c, id);
        qtStage.apply(r, new SubmitInput());
      } catch (Exception e) {
        ok = false;
        writeError("error", e.getMessage() + "\n");
      }
    }

    if (!ok) {
      throw die("one or more stages failed; review output above");
    }
  }
}
