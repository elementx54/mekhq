/*
 * Copyright (c) 2018  - The MegaMek Team
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
package mekhq.campaign.market;

import java.io.PrintWriter;
import java.util.List;

import org.w3c.dom.Node;

import mekhq.campaign.Campaign;
import mekhq.campaign.personnel.Person;

/**
 * Interface to be implemented by methods for generating and removing personnel market entries
 * 
 * @author Neoancient
 *
 */
public interface PersonnelMarketMethod {
    
    String getMethodName();
    List<Person> generatePersonnelForDay(Campaign c);
    List<Person> removePersonnelForDay(Campaign c, List<Person> current);
    
    default void loadFieldsFromXml(Node node) {}
    default void writeToXml(PrintWriter pw1, int indent) {}

}
