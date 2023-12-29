//
// Copyright (C) 2023-24 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.account.AccountResource;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.text.StringEscapeUtils;

@Singleton
public class QtGetCiStatus implements RestReadView<AccountResource> {

  @Inject private QtCiStatusStorage statusStorage;

  private static String ciStatusUnknownColor = "var(--disabled-foreground)";

  public static class StatusInfo {
    public String message;
    public String statusColor;
  }

  @Override
  public Response<StatusInfo> apply(AccountResource rsrc) {

    StatusInfo statusInfo = new StatusInfo();
    statusInfo.message = "";
    statusInfo.statusColor = ciStatusUnknownColor;
    if (!rsrc.getUser().isIdentifiedUser()) return Response.ok(statusInfo);

    QtCiStatusStorage.CiResourceItem items[] = statusStorage.getData();
    statusInfo.message = generateHtml(items);
    statusInfo.statusColor = maxLoadColor(items);

    return Response.ok(statusInfo);
  }

  private String generateHtml(QtCiStatusStorage.CiResourceItem items[]) {

    String html = "";
    html += "<style>";
    html +=   ".cistatuslist { border-top: 1px solid var(--border-color);";
    html +=     " padding: var(--spacing-m) var(--spacing-l); }";
    html +=   ".cistatusitem { font-size: var(--font-size-small);";
    html +=     " margin-left: var(--spacing-l);}";
    html += "</style>";
    html += "<div class=\"dropdown-content\">";
    html +=   "<span style=\"padding: var(--spacing-m) var(--spacing-l); display: block;";
    html +=     " background-color: var(--selection-background-color);\">";

    if (items != null) {
      html += "Workload</span>";
      for (QtCiStatusStorage.CiResourceItem r : items) {
        html += generateSectionHtml(r.name, r.running, r.queue, r.load);
      }
    } else {
      html += "Not updated</span>"; ;
    }
    html += "<div>";

    return html;
  }

  private String generateSectionHtml(String name, Integer running, Integer queue, Integer load) {
    String html = "";
    html += "<div class=\"cistatuslist\">" + StringEscapeUtils.escapeHtml4(name);
    html +=   "<div class=\"cistatusitem\">";
    html +=     "<span><b>" + running + "</b> running</span><br>";
    html +=     "<span style=\"color: " + loadColor(load) + ";\">";
    html +=        "<b>" + queue + "</b>";
    html +=     "</span>";
    html +=     "<span> in queue</span>";
    html +=   "</div>";
    html += "</div>";
    return html;
  }

  private String maxLoadColor(QtCiStatusStorage.CiResourceItem items[]) {
    Integer load = -1;
    if (items != null) {
      for (QtCiStatusStorage.CiResourceItem r : items) {
        if (r.load > load) load = r.load;
      }
    }
    return loadColor(load);
  }

  private String loadColor(Integer load) {
    var color = ciStatusUnknownColor;
    if (load == 0) color = "var(--success-foreground)";
    else if (load == 1) color = "var(--warning-foreground)";
    else if (load > 1) color = "var(--error-foreground)";
    return color;
  }
}
