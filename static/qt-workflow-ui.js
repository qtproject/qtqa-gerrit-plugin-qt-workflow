//
// Copyright (C) 2019-23 The Qt Company
//
// This plugin provides UI customization for codereview.qt-project.org
//

'use strict';

var BUTTONS = [
    { key: 'gerrit-plugin-qt-workflow~abandon', icon: "block" },
    { key: 'gerrit-plugin-qt-workflow~defer', icon: "watch_later" },
    { key: 'gerrit-plugin-qt-workflow~reopen', icon: "history" },
    { key: 'gerrit-plugin-qt-workflow~stage', icon: "done_all" },
    { key: 'gerrit-plugin-qt-workflow~unstage', icon: "undo" },
    { key: 'gerrit-plugin-qt-workflow~precheck', icon: "preview" }
];

Gerrit.install(plugin => {

    plugin.buttons = null;

    var CiStatusColorElement = null;
    var CiStatusElement = null;
    var CiStatusMessage = null;
    var CiStatusMessageNew = null;
    var CiStatusColor = null;

    // Ensure comment dialog component is registered.
    function createCommentDialog() {
        var commentDialog = customElements.get('comment-dialog')
        if (commentDialog) {
            return
        }

        Polymer({
            is: 'comment-dialog',

            ready: function() {
                this.innerHTML = `
                <style>
                    .main {
                        left: 50%;
                        top: 50%;
                        transform: translate(-50%, -50%);
                        display: flex;
                        border-radius: 4px;
                        border: 0px;
                        padding: 16px;
                        box-shadow: 0px 4px 4px 0px rgb(60 64 67 / 30%), 0px 8px 12px 6px rgb(60 64 67 / 15%);
                        position: absolute;
                    }
                    .overflow-container {
                        min-width: 32em;
                        min-height: 8em;
                    }
                    .footer {
                        display: flex;
                        justify-content: flex-end;
                        padding-top: var(--spacing-l);
                    }
                    paper-button {
                        color: #1565c0
                    }
                    paper-button:hover {
                        background: #00000016;
                    }
                    #commentdialog {
                        position: fixed;
                    }
                    #CommentInput {
                        font-size: var(--font-size-mono);
                        font-family: var(--monospace-font-family);
                        border: 1px solid var(--border-color);
                        border-radius: 4px;
                        margin-top: var(--spacing-s);
                        padding: 8px;
                        font-size: 14px;
                        min-width: 30em;
                        min-height: 4em;
                        outline: none;
                        resize: vertical;
                    }
                    label {
                        white-space: nowrap;
                        color: var(--deemphasized-text-color);
                        font-weight: var(--font-weight-bold);
                        padding-right: var(--spacing-m);
                    }
                    .paragraph {
                        margin-block-start: 1em;
                        margin-block-end: 1em;
                    }
                    .dialog-title {
                        font-size: 16px;
                        margin-bottom: var(--spacing-m);
                    }
                </style>
                <div id="commentdialog">
                    <dialog class="main">
                    <form is="iron-form" id="commentForm">
                        <div class="overflow-container">
                            <div class="dialog-title" id="dialogTitle">Add Comment</div>
                            <div>
                                <label for="CommentInput">Comment (optional):</label>
                                <textarea id="CommentInput" rows="3" autocapitalize="sentences" placeholder="Enter your comment here..."></textarea>
                            </div>
                        </div>
                        <div class="footer">
                            <paper-button id="confirmBtn" value="default">Confirm</paper-button>
                            <paper-button id="cancelBtn" value="default">Cancel</paper-button>
                        </div>
                    </form>
                    </dialog>
                </div>`;
            }
        });

        plugin.registerDynamicCustomComponent('comment-dialog', 'comment-dialog');
    }

    function onCommentActionBtn(actionUrl, actionTitle) {
        plugin.popup('comment-dialog').then((v) => {
            const dialog = v.popup.querySelector('#commentdialog')
            const confirmBtn = dialog.querySelector('#confirmBtn')
            const cancelBtn = dialog.querySelector('#cancelBtn')
            const titleEl = dialog.querySelector('#dialogTitle')
            const commentInput = dialog.querySelector('#CommentInput')

            // Set dialog title
            titleEl.textContent = actionTitle;

            // Guard against multiple submissions via Ctrl+Enter or repeated clicks.
            let submitting = false;

            // The gerrit plugin popup api does not delete the dom elements
            // a manual deleting is needed or the ids confuse the scripts.
            const ironOverlayHandler = (event) => {
                v.popup.remove();
            };
            document.addEventListener('iron-overlay-canceled', ironOverlayHandler);

            confirmBtn.addEventListener('click', function onConfirm() {
                if (submitting) return;
                submitting = true;

                // Preserve original text/color so we can restore later.
                const confirmOrigText = confirmBtn.textContent;
                const confirmOrigColor = confirmBtn.style.color;
                const cancelOrigColor = cancelBtn.style.color;

                // Update UI to indicate submitting state.
                confirmBtn.disabled = true;
                cancelBtn.disabled = true;
                confirmBtn.setAttribute('loading');
                confirmBtn.textContent = 'Submitting...';
                confirmBtn.style.color = 'var(--deemphasized-text-color)';
                cancelBtn.style.color = 'var(--deemphasized-text-color)';

                const message = commentInput.value.trim();
                const payload = message ? { message: message } : {};

                plugin.restApi().post(actionUrl, payload).then(() => {
                    window.location.reload(true);
                }).catch((failed_resp) => {
                    // Restore UI so user can retry.
                    submitting = false;
                    confirmBtn.removeAttribute('loading');
                    confirmBtn.disabled = false;
                    cancelBtn.disabled = false;
                    confirmBtn.textContent = confirmOrigText;
                    confirmBtn.style.color = confirmOrigColor;
                    cancelBtn.style.color = cancelOrigColor;
                    this.dispatchEvent(
                        new CustomEvent('show-alert', {
                            detail: {message: failed_resp},
                            composed: true,
                            bubbles: true,
                        })
                    );
                });
            });

            cancelBtn.addEventListener('click', function onCancel() {
                if (submitting) return;
                v.close()
                v.popup.remove();
                document.removeEventListener('iron-overlay-canceled', ironOverlayHandler);
            });

            const formEl = dialog.querySelector('#commentForm') || dialog.querySelector('form');
            formEl.addEventListener('submit', (e) => {
                e.preventDefault();
                if (!submitting) confirmBtn.click();
            });

            dialog.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                    e.preventDefault();
                    if (!submitting) confirmBtn.click();
                }
            });

            // Focus the textarea
            commentInput.focus();
        });
    }

    // Ensure precheck dialog component is registered and global overflow listeners are set once.
    function createPrecheck() {
        // Avoids defining precheck module twice which would cause exception.
        // This would happend during some UI actions e.g. opening edit mode.
        var precheck = customElements.get('precheck-dialog')
        if (precheck) {
            return
        }

        Polymer({
            is: 'precheck-dialog',

            ready: function() {
                this.innerHTML = `
                <style>
                    .main {
                        left: 50%;
                        top: 50%;
                        transform: translate(-50%, -50%);
                        display: flex;
                        border-radius: 4px;
                        border: 0px;
                        padding: 16px;
                        box-shadow: 0px 4px 4px 0px rgb(60 64 67 / 30%), 0px 8px 12px 6px rgb(60 64 67 / 15%);
                        position: absolute;
                    }
                    .overflow-container {
                        min-width: 32em;
                        min-height: 12em;
                    }
                    .footer {
                        display: flex;
                        justify-content: flex-end;
                        padding-top: var(--spacing-l);
                    }
                    paper-button {
                        color: #1565c0
                    }
                    paper-button:hover {
                        background: #00000016;
                    }
                    select {
                        color: rgb(33, 33, 33);
                        font-family: var(--font-family, inherit);
                        font-size: 14px;
                        border-radius: 4px;
                        border-color: rgb(218, 220, 224);
                        background-color: rgb(248, 249, 250);
                        padding: 4px;
                        outline: none;
                    }
                    .input-body[hidden] {
                        display: none;
                    }
                    .input {
                        display: flex;
                    }
                    #precheckdialog {
                        position: fixed;
                    }
                    #PlatformsInput {
                        font-size: var(--font-size-mono);
                        font-family: var(--monospace-font-family);
                        border: 1px solid var(--border-color);
                        border-radius: 4px;
                        margin-top: var(--spacing-s);
                        padding: 4px;
                        font-size: 14px;
                        min-width: 30em;
                        outline: none;
                    }
                    label {
                        white-space: nowrap;
                        color: var(--deemphasized-text-color);
                        font-weight: var(--font-weight-bold);
                        padding-right: var(--spacing-m);
                    }
                    .paragraph {
                        margin-block-start: 1em;
                        margin-block-end: 1em;
                    }
                    #BuildOnlyCheckBox, #CherrypickCheckBox{
                        margin: 3px;
                    }
                </style>
                <div id="precheckdialog">
                    <dialog class="main">
                    <form is="iron-form" id="precheckForm">
                        <div class="overflow-container">
                            <div style="font-size: 16px;">Precheck</div>
                            <div><p class="paragraph">Select the precheck type. Default will run targets from precheck.yaml, equal to full if yaml not found.
                                Full will run all targets. Custom will allow manual selection of the targets.</p></div>
                            <div class="input-body">
                                <p class="paragraph"><label>Precheck type:
                                <select id="typeSelect" style="margin-left: 4px;">
                                    <option value="default" title="Runs targets from precheck.yaml (lower coverage but faster)">Default</option>
                                    <option value="full" title="Runs all targets (high coverage but slower)">Full</option>
                                    <option value="downstream" title="Runs a qt5 precheck with this change as dependency">Downstream qt5</option>
                                    <option value="custom">Custom</option>
                                </select>
                                </label></p>
                            </div>
                            <div style="display: flex; flex-direction: column;">
                                <div class="input-body">
                                    <div class="input" title="Excludes tests">
                                        <input type="checkbox" id="BuildOnlyCheckBox"/>
                                        <label for="BuildOnlyCheckBox">Build only</label>
                                    </div>
                                </div>
                                <div class="input-body">
                                    <div class="input" title="Cherry-picks changes instead of checkout">
                                        <input type="checkbox" id="CherrypickCheckBox"/>
                                        <label for="CherrypickCheckBox">Cherry-pick</label>
                                    </div>
                                </div>
                                <div id="checkboxes" hidden=true>
                                    <p class="paragraph">Match against os, osversion, arch, compiler or feature.
                                    See <a href="https://testresults.qt.io/coin/doc/precheck.html">COIN precheck</a> for usage details.</p>
                                    <input type="text" id="PlatformsInput" rows="1" autocapitalize="none" placeholder="os:android and arch:-x86">
                                </div>
                            </div>
                        </div>
                        <div class="footer">
                            <paper-button id="confirmBtn" value="default">Confirm</paper-button>
                            <paper-button id="cancelBtn" value="default">Cancel</paper-button>
                        </div>
                    </form>
                    </dialog>
                </div>`;
            }
        });

        plugin.registerDynamicCustomComponent('precheck-dialog', 'precheck-dialog');
    }

    function onPrecheckBtn(c) {
        plugin.popup('precheck-dialog').then((v) => {
            const dialog = v.popup.querySelector('#precheckdialog')
            const confirmBtn = dialog.querySelector('#confirmBtn')
            const cancelBtn = dialog.querySelector('#cancelBtn')

            // Guard against multiple submissions via Ctrl+Enter or repeated clicks.
            let submitting = false;

            dialog.querySelector('#typeSelect').addEventListener('change', (event) => {
                const typeSelect = event.currentTarget.value;
                const checkboxes = dialog.querySelector('#checkboxes');
                if (typeSelect === 'custom') {
                  checkboxes.hidden = false;
                } else {
                  checkboxes.hidden = true;
                }
            });

            // The gerrit plugin popup api does not delete the dom elements
            // a manual deleting is needed or the ids confuse the scripts.
            const ironOverlayHandler = (event) => {
                v.popup.remove();
            };
            document.addEventListener('iron-overlay-canceled', ironOverlayHandler);

            confirmBtn.addEventListener('click', function onOpen() {
                if (submitting) return;
                submitting = true;

                // Preserve original text/color so we can restore later.
                const confirmOrigText = confirmBtn.textContent;
                const confirmOrigColor = confirmBtn.style.color;
                const cancelOrigColor = cancelBtn.style.color;

                // Update UI to indicate submitting state.
                confirmBtn.disabled = true;
                cancelBtn.disabled = true;
                confirmBtn.setAttribute('loading');
                confirmBtn.textContent = 'Submitting...';
                confirmBtn.style.color = 'var(--deemphasized-text-color)';
                cancelBtn.style.color = 'var(--deemphasized-text-color)';

                const actions = plugin.__precheckActions || {};
                const preAction = actions["gerrit-plugin-qt-workflow~precheck"];
                const url = preAction && preAction.__url;

                if (!url) {
                    // Restore UI so user can retry.
                    submitting = false;
                    confirmBtn.removeAttribute('loading');
                    confirmBtn.disabled = false;
                    cancelBtn.disabled = false;
                    confirmBtn.textContent = confirmOrigText;
                    confirmBtn.style.color = confirmOrigColor;
                    cancelBtn.style.color = cancelOrigColor;
                    this.dispatchEvent(
                        new CustomEvent('show-alert', {
                            detail: {message: 'Precheck action is not available.'},
                            composed: true,
                            bubbles: true,
                        })
                    );
                    return;
                }

                plugin.restApi().post(url, {
                        type: dialog.querySelector('#typeSelect').value,
                        onlybuild: dialog.querySelector('#BuildOnlyCheckBox').checked,
                        cherrypick: dialog.querySelector('#CherrypickCheckBox').checked,
                        platforms: dialog.querySelector('#PlatformsInput').value,
                    }).then(() => {
                            window.location.reload(true);
                    }).catch((failed_resp) => {
                        // Restore UI so user can retry.
                        submitting = false;
                        confirmBtn.removeAttribute('loading');
                        confirmBtn.disabled = false;
                        cancelBtn.disabled = false;
                        confirmBtn.textContent = confirmOrigText;
                        confirmBtn.style.color = confirmOrigColor;
                        cancelBtn.style.color = cancelOrigColor;
                        this.dispatchEvent(
                            new CustomEvent('show-alert', {
                                detail: {message: failed_resp},
                                composed: true,
                                bubbles: true,
                            })
                        );
                });
            });

            cancelBtn.addEventListener('click', function onOpen() {
                if (submitting) return;
                v.close()
                v.popup.remove();
                document.removeEventListener('iron-overlay-canceled', ironOverlayHandler);
            });

            const formEl = dialog.querySelector('#precheckForm') || dialog.querySelector('form');
            formEl.addEventListener('submit', (e) => {
                e.preventDefault();
                if (!submitting) confirmBtn.click();
            });

            dialog.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                    e.preventDefault();
                    if (!submitting) confirmBtn.click();
                }
            });
        });
    }

    // Register dialog components now (idempotent)
    createCommentDialog();
    createPrecheck();

    // Register global listeners once for overflow actions and fallback clicks.
    if (!plugin.__precheckTapBound) {
        plugin.__precheckTapBound = true;

        const precheckTapHandler = (e) => {
            e.preventDefault();
            e.stopPropagation();
            e.stopImmediatePropagation();
            onPrecheckBtn();
        };

        document.addEventListener('gerrit-plugin-qt-workflow~precheck-revision-tap', precheckTapHandler, true);
        document.addEventListener('gerrit-plugin-qt-workflow~precheck-change-tap', precheckTapHandler, true);

        // Fallback for environments not emitting custom tap events.
        document.addEventListener('click', (e) => {
            const path = e.composedPath ? e.composedPath() : [e.target];
            for (let i = 0; i < path.length; i++) {
                const node = path[i];
                if (!node || !node.classList || !node.classList.contains) continue;
                if (node.classList.contains('itemAction')) {
                    const dataId = node.getAttribute && node.getAttribute('data-id');
                    if (dataId === 'gerrit-plugin-qt-workflow~precheck-revision') {
                        e.preventDefault();
                        e.stopPropagation();
                        e.stopImmediatePropagation();
                        onPrecheckBtn();
                        break;
                    }
                }
            }
        }, true);
    }

    function htmlToElement(html) {
        var template = document.createElement('template');
        html = html.trim(); // No white space
        template.innerHTML = html;
        return template.content.firstChild;
    }

    // Customize header
    plugin.hook('header-title',  {replace: true} ).onAttached(element => {
        const css_str = '<style> \
                          .QtTitleText::before {\
                          background-image: url("/static/logo_qt.png");\
                          background-size: 40px 30px;\
                          background-repeat: no-repeat;\
                          content: "";\
                          display: inline-block;\
                          height: 36px;\
                          vertical-align: text-top;\
                          width: 46px;\
                        }\
                        </style>';
        const html_str = '<div id="qt-header"> \
                            <div class="QtTitleText">Code Review</div> \
                          </div>';
        var elem = htmlToElement(css_str);
        element.appendChild(elem);
        elem = htmlToElement(html_str);
        element.appendChild(elem);
    });

    // Hide Sanity Bot review score row by default in reply dialog
    plugin.hook('review-label-scores-sanity-review').onAttached(element => {
        const html = '<div id="review-label-scores-sanity-review-more-button"> \
                          <div id="sanitybotreviewmorediv" class="labelNameCell" style="display:block;">more...</div> \
                          <div id="sanitybotreviewscorediv" style="display:none;"></div> \
                      </div>';
        var wrapper_elem = document.createElement('div');
        wrapper_elem.innerHTML = html;

        // Place the sanity review label elements inside the new wrapper element.
        // When upgrading to a new Gerrit release use, "console.log(element)" to debug structure of the elements
        var sanity_elem_root = element.parentNode;
        var child_elem;
        while (sanity_elem_root.childNodes.length) {
            child_elem = sanity_elem_root.removeChild(sanity_elem_root.childNodes[0]);
            wrapper_elem.querySelector("#sanitybotreviewscorediv").appendChild(child_elem);
        }
        sanity_elem_root.appendChild(wrapper_elem);

        // Install click listener to show the score buttons when clicking on more.
        var more_button_handler = wrapper_elem.querySelector("#review-label-scores-sanity-review-more-button");
        var more_button_div = wrapper_elem.querySelector("#sanitybotreviewmorediv");
        var review_score_div = wrapper_elem.querySelector("#sanitybotreviewscorediv");
        more_button_handler.addEventListener("click", function() {
            more_button_div.style.display = "none";
            review_score_div.style.display = "block";
        });
    });

    // Show the Security Sensitive banner under the commit message when the hashtag is present
    plugin.hook('commit-container').onAttached(element => {
        const hashtag = element.change.hashtags
            ? element.change.hashtags.includes("Qt-Security change")
            : false;
        if (hashtag) {
            const el = document.createElement('div');
            el.textContent = "Change affects security critical files";
            el.style = `color: var(--error-foreground);
                        background: var(--error-background);
                        padding: var(--spacing-s) var(--spacing-l);
                        border: 1px solid darkred;
                        border-radius: var(--border-radius);
                        font-size: large;
                        font-weight: bold;
                        text-align: center;`;
            element.appendChild(el);
        }
    });

    // Customize change view
    plugin.on('show-revision-actions', function(revisionActions, changeInfo) {
        var actions = Object.assign({}, revisionActions, changeInfo.actions);
        plugin.__precheckActions = actions;
        var cActions = plugin.changeActions();

        // Hide 'Sanity-Review+1' button in header
        let secondaryActions = cActions.el.__topLevelSecondaryActions;
        if (secondaryActions && Array.isArray(secondaryActions)) {
            secondaryActions.forEach((action) => {
                if (action.__key === "review" && action.label === "Sanity-Review+1") {
                    cActions.hideQuickApproveAction();
                }
            });
        }

        // Remove any existing buttons
        if (plugin.buttons) {
            BUTTONS.forEach((button) => {
                let key = button.key;
                if (typeof plugin.buttons[key] !== 'undefined' && plugin.buttons[key] !== null) {
                    cActions.removeTapListener(plugin.buttons[key], (param) => {} );
                    cActions.remove(plugin.buttons[key]);
                    plugin.buttons[key] = null;
                }
            });
        } else plugin.buttons = [];



        // Add buttons based on server response
        BUTTONS.forEach((button) => {
            let key = button.key;
            let action = actions[key];
            if (action) {
                // add button
                plugin.buttons[key] = cActions.add(action.__type, action.label);

                // hide dropdown action (only after button is created)
                cActions.setActionHidden(action.__type, action.__key, true);
                cActions.setIcon(plugin.buttons[key], button.icon);
                cActions.setTitle(plugin.buttons[key], action.title);
                cActions.setEnabled(plugin.buttons[key], action.enabled===true);
                if (key === 'gerrit-plugin-qt-workflow~precheck') {
                    createPrecheck()
                    cActions.addTapListener(plugin.buttons[key], onPrecheckBtn);

                } else {
                    cActions.addTapListener(plugin.buttons[key], buttonEventCallback);
                }

                if (key === 'gerrit-plugin-qt-workflow~stage') {
                    // hide submit button when it would be disabled next to the stage button
                    let submit = actions['submit'];
                    if (!submit.enabled) {
                        cActions.setActionHidden('revision', 'submit', true);
                    }
                }

            }
        });

        function buttonEventCallback(event) {
            var button_key = event.type.substring(0, event.type.indexOf('-tap'));
            var button_action = null;
            var button_index;
            for (var k in plugin.buttons) {
                if (plugin.buttons[k] === button_key) {
                    button_action = actions[k];
                    button_index = k;
                    break;
                }
            }
            if (button_action) {
                // Check if this action should show a comment dialog
                const actionsWithDialog = [
                    'gerrit-plugin-qt-workflow~defer',
                    'gerrit-plugin-qt-workflow~reopen',
                    'gerrit-plugin-qt-workflow~abandon',
                    'gerrit-plugin-qt-workflow~unstage'
                ];

                if (actionsWithDialog.includes(button_index)) {
                    // Show comment dialog for these actions
                    createCommentDialog();
                    onCommentActionBtn(button_action.__url, button_action.title);
                    return;
                }

                // For other actions, proceed with immediate post (e.g., stage, unstage)
                const buttonEl = this.shadowRoot.querySelector(`[data-action-key="${button_key}"]`);
                // Preserve original text/color to restore later.
                buttonEl.dataset._origText = buttonEl.textContent;
                buttonEl.dataset._origColor = buttonEl.style.color || '';
                buttonEl.setAttribute('loading', true);
                buttonEl.disabled = true;
                // Visually deemphasize while submitting and show status text.
                buttonEl.textContent = 'Submitting...';
                buttonEl.style.color = 'var(--deemphasized-text-color)';

                plugin.restApi().post(button_action.__url, {})
                    .then((ok_resp) => {
                        buttonEl.removeAttribute('loading');
                        buttonEl.disabled = false;
                        // Restore original text/color (best-effort before reload).
                        buttonEl.textContent = buttonEl.dataset._origText;
                        buttonEl.style.color = buttonEl.dataset._origColor;
                        window.location.reload(true);
                    }).catch((failed_resp) => {
                        buttonEl.removeAttribute('loading');
                        buttonEl.disabled = false;
                        // Restore UI so user can retry.
                        buttonEl.textContent = buttonEl.dataset._origText;
                        buttonEl.style.color = buttonEl.dataset._origColor;
                        this.dispatchEvent(
                            new CustomEvent('show-alert', {
                                detail: {message: failed_resp},
                                composed: true,
                                bubbles: true,
                            })
                        );
                    });
            } else console.log('unexpected error: no action');
        }
    });

    function updateCiStatusUI() {
        if (CiStatusColorElement && CiStatusColor) CiStatusColorElement.style.color = CiStatusColor;
        if (CiStatusElement && CiStatusMessage !== CiStatusMessageNew ) {
            const elem = document.createElement('div');
            elem.innerHTML = CiStatusMessageNew.trim(); // No white space;
            CiStatusElement.parentElement.appendChild(elem);
            CiStatusElement.nextElementSibling.style.display = "none";
            CiStatusMessage = CiStatusMessageNew;
        }
    }

    plugin.restApi().get('/accounts/self/gerrit-plugin-qt-workflow~cistatus')
        .then((ok_resp) => {
            if (ok_resp.message) CiStatusMessageNew = ok_resp.message;
            if (ok_resp.status_color) CiStatusColor = ok_resp.status_color;
            updateCiStatusUI();
        }).catch((failed_resp) => {
            // use defaults
        });

    plugin.hook('main-header-ci-status').onAttached(element => {
        var rootElem = element.parentElement.parentElement.shadowRoot;
        CiStatusElement = rootElem.querySelector(".itemAction");
        var button = rootElem.querySelector("gr-button");
        CiStatusColorElement = button.shadowRoot.querySelector("gr-icon");
        updateCiStatusUI();
    });
});
