//
// Copyright (C) 2019-22 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.server.git.ChangeMessageModifier;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eclipse.jgit.revwalk.RevCommit;

@Singleton
public class QtChangeMessageModifier implements ChangeMessageModifier {

  @Inject private com.google.gerrit.server.config.PluginConfigFactory cfg;

  // Remove extra commit message footers
  @Override
  public String onSubmit(
      String commitMessage, RevCommit original, RevCommit mergeTip, BranchNameKey destination) {
    StringBuilder stringBuilder = new StringBuilder("");
    String[] lines = commitMessage.split("\n");

    boolean showReviewedOn = false;
    try {
      showReviewedOn =
          cfg.getFromProjectConfigWithInheritance(
                  destination.project(), "gerrit-plugin-qt-workflow")
              .getBoolean("showReviewedOnFooter", false);
    } catch (NoSuchProjectException e) {
      // Project not found, using default
    }

    boolean first_line = true;
    for (String line : lines) {
      if (((!line.startsWith("Reviewed-on: ") || showReviewedOn == true)
              && !line.startsWith("Tested-by: ")
              && !line.startsWith("Sanity-Review: ")
              && !line.startsWith("ChangeLog: "))
          || first_line) {
        stringBuilder.append(line).append("\n");
        first_line = false;
      }
    }
    return stringBuilder.toString();
  }
}
