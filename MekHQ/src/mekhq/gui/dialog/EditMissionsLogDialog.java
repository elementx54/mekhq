/*
 * Copyright (c) 2019 The MegaMek Team. All rights reserved.
 *
 * This file is part of MekHQ.
 *
 * MekHQ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MekHQ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MekHQ.  If not, see <http://www.gnu.org/licenses/>.
 */

package mekhq.gui.dialog;

import megamek.common.util.EncodeControl;
import mekhq.campaign.Campaign;
import mekhq.campaign.personnel.Person;
import mekhq.gui.control.EditMissionsLogControl;

import javax.swing.*;
import java.awt.*;
import java.util.ResourceBundle;

public class EditMissionsLogDialog extends JDialog {
    private Frame frame;
    private Campaign campaign;
    private Person person;

    private EditMissionsLogControl editMissionsControl;
    private JButton btnOK;

    /**
     * Creates new form EditPersonnelLogDialog
     */
    public EditMissionsLogDialog(Frame parent, boolean modal, Campaign campaign, Person person) {
        super(parent, modal);
        this.frame = parent;
        this.campaign = campaign;
        this.person = person;

        initComponents();
        setLocationRelativeTo(parent);
    }

    private void initComponents() {
        ResourceBundle resourceMap = ResourceBundle.getBundle("mekhq.resources.EditMissionsLogDialog", new EncodeControl()); //$NON-NLS-1$

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setName(resourceMap.getString("dialog.name")); // NOI18N
        setTitle(resourceMap.getString("dialog.title") + " " + person.getName());
        getContentPane().setLayout(new java.awt.BorderLayout());

        editMissionsControl = new EditMissionsLogControl(frame, campaign, person);
        getContentPane().add(editMissionsControl, BorderLayout.CENTER);

        btnOK = new JButton();
        btnOK.setText(resourceMap.getString("btnOK.text")); // NOI18N
        btnOK.setName("btnOK"); // NOI18N
        btnOK.addActionListener(x -> this.setVisible(false));
        getContentPane().add(btnOK, BorderLayout.PAGE_END);

        pack();
    }
}
