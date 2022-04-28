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
                cActions.addTapListener(plugin.buttons[key], buttonEventCallback);

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