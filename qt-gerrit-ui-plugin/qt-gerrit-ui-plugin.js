//
// Copyright (C) 2019-22 The Qt Company
//
// This plugin provides UI customization for codereview.qt-project.org
//

'use strict';

var BUTTONS = [
    'gerrit-plugin-qt-workflow~abandon',
    'gerrit-plugin-qt-workflow~defer',
    'gerrit-plugin-qt-workflow~reopen',
    'gerrit-plugin-qt-workflow~stage',
    'gerrit-plugin-qt-workflow~unstage',
    'gerrit-plugin-qt-workflow~precheck'
];

Gerrit.install(plugin => {

    plugin.buttons = null;

    function htmlToElement(html) {
        var template = document.createElement('template');
        html = html.trim(); // No white space
        template.innerHTML = html;
        return template.content.firstChild;
    }

    // Customize header
    plugin.hook('header-title',  {replace: true} ).onAttached(element => {
        const css_str = '<style> \
                          .titleText::before {\
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
                            <div class="titleText">Code Review</div> \
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
        var sanity_elem_root = element.parentNode.host;
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

    // Customize change view
    plugin.on('show-revision-actions', function(revisionActions, changeInfo) {
        var actions = Object.assign({}, revisionActions, changeInfo.actions);
        var cActions = plugin.changeActions();

        // always hide the rebase button
        cActions.setActionHidden('revision', 'rebase', true);

        // Hide 'Sanity-Review+1' button in header
        var secondaryActionsElem = cActions.el.root;
        if (secondaryActionsElem &&
            secondaryActionsElem.innerHTML &&
            secondaryActionsElem.innerHTML.indexOf('Sanity-Review+1') != -1) {
            cActions.hideQuickApproveAction();
        }

        // Remove any existing buttons
        if (plugin.buttons) {
            BUTTONS.forEach((key) => {
                if (typeof plugin.buttons[key] !== 'undefined' && plugin.buttons[key] !== null) {
                    cActions.removeTapListener(plugin.buttons[key], (param) => {} );
                    cActions.remove(plugin.buttons[key]);
                    plugin.buttons[key] = null;
                }
            });
        } else plugin.buttons = [];

        function onPrecheckBtn(c) {
            plugin.popup('precheck-dialog').then((v) => {
                const dialog = v.popup.querySelector('#precheckdialog')
                const confirmBtn = dialog.querySelector('#confirmBtn')
                const cancelBtn = dialog.querySelector('#cancelBtn')

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
                document.addEventListener('iron-overlay-canceled', (event) => {
                    v.popup.remove();
                });

                confirmBtn.addEventListener('click', function onOpen() {
                    confirmBtn.disabled = true
                    confirmBtn.setAttribute('loading');

                    plugin.restApi().post(actions["gerrit-plugin-qt-workflow~precheck"].__url, {
                        message: "type:" + dialog.querySelector('#typeSelect').value + "&"
                               + "buildonly:" + dialog.querySelector('#BuildOnlyCheckBox').checked + "&"
                               + "platforms:" + dialog.querySelector('#PlatformsInput').value
                        }).then(() => {
                                confirmBtn.removeAttribute('loading');
                                confirmBtn.disabled = false;
                                window.location.reload(true);
                        }).catch((failed_resp) => {
                            this.fire('show-alert', {message: 'FAILED: ' + failed_resp});
                    });
                });

                cancelBtn.addEventListener('click', function onOpen() {
                    v.close()
                    v.popup.remove();
                });
            });
        }

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
                    </style>
                    <div id="precheckdialog">
                        <dialog class="main">
                        <form is="iron-form">
                            <div class="overflow-container">
                                <div style="font-size: 16px;">Precheck</div>
                                <div><p>Select the precheck type. Default will run targets from precheck.yaml, equal to full if yaml not found.
                                    Full will run all targets. Custom will allow manual selection of the targets.</p></div>
                                <div class="input-body">
                                    <p><label>Precheck type:
                                    <select id="typeSelect" style="margin-left: 4px;">
                                        <option value="default" title="Runs targets from precheck.yaml (lower coverage but faster)">Default</option>
                                        <option value="full" title="Runs all targets (high coverage but slower)">Full</option>
                                        <option value="custom">Custom</option>
                                    </select>
                                    </label></p>
                                </div>
                                <div style="display: flex; flex-direction: column;">
                                    <div class="input-body">
                                        <div class=input title="Excludes tests">
                                            <input type="checkbox" id="BuildOnlyCheckBox"/>
                                            <label for="BuildOnlyCheckBox">Build only</label>
                                        </div>
                                    </div>
                                    <div id="checkboxes" hidden=true>
                                        <p>Targets can be a bare OS name, or OS with version.
                                        See <a href="https://testresults.qt.io/coin/doc/properties.html#os">COIN Platforms</a> for available targets.
                                        If a given target (case-insensitive) matches with an available target, it will be selected for running.
                                        Linux as target is a special case and matches to all linux distros and versions.</p>
                                        <input type="text" id="PlatformsInput" rows="1" autocapitalize="none" placeholder="linux,android,qnx">
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

        // Add buttons based on server response
        BUTTONS.forEach((key) => {
            var action = actions[key];
            if (action) {
                // hide dropdown action
                cActions.setActionHidden(action.__type, action.__key, true);

                // add button
                plugin.buttons[key] = cActions.add(action.__type, action.label);
                cActions.setTitle(plugin.buttons[key], action.title);
                cActions.setEnabled(plugin.buttons[key], action.enabled===true);
                if (key === 'gerrit-plugin-qt-workflow~precheck') {
                    createPrecheck()
                    cActions.addTapListener(plugin.buttons[key], onPrecheckBtn);
                } else {
                    cActions.addTapListener(plugin.buttons[key], buttonEventCallback);
                }

                if (key === 'gerrit-plugin-qt-workflow~stage') {
                    // use the submit icon for our stage button
                    cActions.setIcon(plugin.buttons[key], 'submit');
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
                const buttonEl = this.shadowRoot.querySelector(`[data-action-key="${button_key}"]`);
                buttonEl.setAttribute('loading', true);
                buttonEl.disabled = true;
                plugin.restApi().post(button_action.__url, {})
                    .then((ok_resp) => {
                        buttonEl.removeAttribute('loading');
                        buttonEl.disabled = false;
                        window.location.reload(true);
                    }).catch((failed_resp) => {
                        buttonEl.removeAttribute('loading');
                        buttonEl.disabled = false;
                        this.fire('show-alert', {message: 'FAILED: ' + failed_resp});
                    });
            } else console.log('unexpected error: no action');
        }
    });
});
