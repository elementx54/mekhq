/*
 * PersonnelMarket.java
 *
 * Copyright (c) 2013 Dylan Myers <dylan at dylanspcs.com>. All rights reserved.
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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import classes.mekhq.campaign.event.OptionsChangedEvent;
import megamek.common.Compute;
import megamek.common.Entity;
import megamek.common.EntityMovementMode;
import megamek.common.EntityWeightClass;
import megamek.common.MechFileParser;
import megamek.common.MechSummary;
import megamek.common.MechSummaryCache;
import megamek.common.TargetRoll;
import megamek.common.UnitType;
import megamek.common.event.Subscribe;
import megamek.common.loaders.EntityLoadingException;
import megamek.common.logging.LogLevel;
import mekhq.MekHQ;
import mekhq.MekHqXmlUtil;
import mekhq.Utilities;
import mekhq.Version;
import mekhq.campaign.Campaign;
import mekhq.campaign.finances.Transaction;
import mekhq.campaign.personnel.Person;
import mekhq.campaign.personnel.SkillType;
import mekhq.campaign.rating.IUnitRating;
import mekhq.campaign.universe.Faction;
import mekhq.campaign.universe.RandomFactionGenerator;

public class PersonnelMarket {
	private ArrayList<Person> personnel = new ArrayList<Person>();
	private Hashtable<UUID, Person> personnelIds = new Hashtable<UUID, Person>();
	private PersonnelMarketMethod method;
	private int methodIndex = TYPE_RANDOM;

	public static final int TYPE_RANDOM = 0;
	public static final int TYPE_DYLANS = 1;
	public static final int TYPE_FMMR = 2;
	public static final int TYPE_STRAT_OPS = 3;
	public static final int TYPE_ATB = 4;
	public static final int TYPE_NUM = 5;

	/* Used by AtB to track Units assigned to recruits; the key
	 * is the person UUID. */
	private Hashtable<UUID, Entity> attachedEntities = new Hashtable<UUID, Entity>();
	/* Alternate types of rolls, set by PersonnelMarketDialog */
	private boolean paidRecruitment = false;
	private int paidRecruitType;

	public PersonnelMarket() {
	    method = new PersonnelMarketRandom();
        MekHQ.registerHandler(this);
	}

	public PersonnelMarket(Campaign c) {
		generatePersonnelForDay(c);
		setType(c.getCampaignOptions().getPersonnelMarketType());
		MekHQ.registerHandler(this);
	}
	
	/**
	 * Sets the method for generating potential recruits for the personnel market.
	 * @param type  The index of the market type to use.
	 */
	public void setType(int type) {
	    // We don't want to initialize the method if it doesn't actually change, since methods
	    // can lose state.
	    if ((type != methodIndex) || (null == method)) {
            switch (type) {
                case TYPE_RANDOM:
                    method = new PersonnelMarketRandom();
                    break;
                case TYPE_DYLANS:
                    method = new PersonnelMarketDylan();
                    break;
                case TYPE_FMMR:
                    method = new PersonnelMarketFMMr();
                    break;
                case TYPE_STRAT_OPS:
                    method = new PersonnelMarketStratOps();
                    break;
                default:
                    method = null;
            }
            methodIndex = type;
	    }
	}
	
	@Subscribe
	public void handleCampaignOptionsEvent(OptionsChangedEvent ev) {
	    setType(ev.getOptions().getPersonnelMarketType());
	}

	/*
	 * Generate new personnel to be added to the
	 * market availability pool
	 */
	public void generatePersonnelForDay(Campaign c) {
		int roll;
		Person p;
		boolean updated = false;

		if (!personnel.isEmpty()) {
			removePersonnelForDay(c);
		}

		if (paidRecruitment && c.getCalendar().get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY) {
			if (c.getFinances().debit(100000, Transaction.C_MISC,
					"Paid recruitment roll", c.getDate())) {
				doPaidRecruitment(c);
				updated = true;
			} else {
				c.addReport("<html><font color=\"red\">Insufficient funds for paid recruitment.</font></html>");
			}
		} else if (null != method) {
		    List<Person> newPersonnel = method.generatePersonnelForDay(c);
		    if (null != newPersonnel) {
    		    for (Person recruit : newPersonnel) {
                    personnel.add(recruit);
                    personnelIds.put(recruit.getId(), recruit);
                    updated = true;
                    if (c.getCampaignOptions().getUseAtB()) {
                        addRecruitUnit(recruit, c);
                    }
    		    }
    		    updated = !newPersonnel.isEmpty();
		    }
		} else {

			switch (c.getCampaignOptions().getPersonnelMarketType()) {
			case TYPE_ATB:
				p = null;

				if (c.getCalendar().get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY) {
					roll = Compute.d6(2);
					if (roll == 2) {
						switch (Compute.randomInt(4)) {
						case 0:
							p = c.newPerson(Person.T_ADMIN_COM);
							break;
						case 1:
							p = c.newPerson(Person.T_ADMIN_HR);
							break;
						case 2:
							p = c.newPerson(Person.T_ADMIN_LOG);
							break;
						case 3:
							p = c.newPerson(Person.T_ADMIN_TRA);
							break;
						}
					} else if (roll == 3 || roll == 11) {
						int r = Compute.d6();
						if (r == 1 && c.getCalendar().get(Calendar.YEAR) >
						(c.getFaction().isClan()?2870:3050)) {
							p = c.newPerson(Person.T_BA_TECH);
						} else if (r < 4) {
							p = c.newPerson(Person.T_MECHANIC);
						} else if (r == 4 && c.getCampaignOptions().getUseAero()) {
							p = c.newPerson(Person.T_AERO_TECH);
						} else {
							p = c.newPerson(Person.T_MECH_TECH);
						}
					} else if (roll == 4 || roll == 10) {
						p = c.newPerson(Person.T_MECHWARRIOR);
					} else if (roll == 5 && c.getCampaignOptions().getUseAero()) {
						p = c.newPerson(Person.T_AERO_PILOT);
					} else if (roll == 5 && c.getFaction().isClan()) {
						p = c.newPerson(Person.T_MECHWARRIOR);
					} else if (roll == 5 || roll == 10) {
						int r = Compute.d6(2);
						if (r == 2) {
							p = c.newPerson(Person.T_VTOL_PILOT);
							//Frequency based on frequency of VTOLs in Xotl 3028 Merc/General
						} else if (r <= 5) {
							p = c.newPerson(Person.T_GVEE_DRIVER);
						} else {
							p = c.newPerson(Person.T_VEE_GUNNER);
						}
					} else if (roll == 6 || roll == 8) {
						if (c.getFaction().isClan() &&
								c.getCalendar().get(Calendar.YEAR) > 2870 &&
								Compute.d6(2) > 3) {
							p = c.newPerson(Person.T_BA);
						} else if (!c.getFaction().isClan() &&
								c.getCalendar().get(Calendar.YEAR) > 3050 &&
								Compute.d6(2) > 11) {
							p = c.newPerson(Person.T_BA);
						} else {
							p = c.newPerson(Person.T_INFANTRY);
						}
					} else if (roll == 12) {
						p = c.newPerson(Person.T_DOCTOR);
					}

					if (null != p) {
						UUID id = UUID.randomUUID();
						while (null != personnelIds.get(id)) {
							id = UUID.randomUUID();
						}
						p.setId(id);
						personnel.add(p);
						personnelIds.put(id, p);
						addRecruitUnit(p, c);

						if (p.getPrimaryRole() == Person.T_GVEE_DRIVER) {
							/* Replace driver with 1-6 crew with equal
							 * chances of being drivers or gunners */
							personnel.remove(p);
							for (int i = 0; i < Compute.d6(); i++) {
								if (Compute.d6() < 4) {
									p = c.newPerson(Person.T_GVEE_DRIVER);
								} else {
									p = c.newPerson(Person.T_VEE_GUNNER);
								}
								p = c.newPerson((Compute.d6() < 4)?Person.T_GVEE_DRIVER:Person.T_VEE_GUNNER);
								if (c.getCampaignOptions().useAbilities()) {
									int nabil = Math.max(0, p.getExperienceLevel(false) - SkillType.EXP_REGULAR);
									while (nabil > 0 && null != c.rollSPA(p.getPrimaryRole(), p)) {
										nabil--;
									}
								}
								id = UUID.randomUUID();
								while (null != personnelIds.get(id)) {
									id = UUID.randomUUID();
								}
								p.setId(id);
								personnel.add(p);
								personnelIds.put(id, p);
							}
						}

						Person adminHR = c.findBestInRole(Person.T_ADMIN_HR, SkillType.S_ADMIN);
						int adminHRExp = (adminHR == null)?SkillType.EXP_ULTRA_GREEN:adminHR.getSkill(SkillType.S_ADMIN).getExperienceLevel();
						int gunneryMod = 0;
						int pilotingMod = 0;
						switch (adminHRExp) {
						case SkillType.EXP_ULTRA_GREEN:
							gunneryMod = -1;
							pilotingMod = -1;
							break;
						case SkillType.EXP_GREEN:
							if (Compute.d6() < 4) {
								gunneryMod = -1;
							} else {
								pilotingMod = -1;
							}
							break;
						case SkillType.EXP_VETERAN:
							if (Compute.d6() < 4) {
								gunneryMod = 1;
							} else {
								pilotingMod = 1;
							}
							break;
						case SkillType.EXP_ELITE:
							gunneryMod = 1;
							pilotingMod = 1;
						}

						switch (p.getPrimaryRole()) {
						case Person.T_MECHWARRIOR:
							adjustSkill(p, SkillType.S_GUN_MECH, gunneryMod);
							adjustSkill(p, SkillType.S_PILOT_MECH, pilotingMod);
							break;
						case Person.T_GVEE_DRIVER:
							adjustSkill(p, SkillType.S_PILOT_GVEE, pilotingMod);
							break;
						case Person.T_NVEE_DRIVER:
							adjustSkill(p, SkillType.S_PILOT_NVEE, pilotingMod);
							break;
						case Person.T_VTOL_PILOT:
							adjustSkill(p, SkillType.S_PILOT_VTOL, pilotingMod);
							break;
						case Person.T_VEE_GUNNER:
							adjustSkill(p, SkillType.S_GUN_VEE, gunneryMod);
							break;
						case Person.T_AERO_PILOT:
							adjustSkill(p, SkillType.S_GUN_AERO, gunneryMod);
							adjustSkill(p, SkillType.S_PILOT_AERO, pilotingMod);
							break;
						case Person.T_INFANTRY:
							adjustSkill(p, SkillType.S_SMALL_ARMS, gunneryMod);
							adjustSkill(p, SkillType.S_ANTI_MECH, pilotingMod);
							break;
						case Person.T_BA:
							adjustSkill(p, SkillType.S_GUN_BA, gunneryMod);
							adjustSkill(p, SkillType.S_ANTI_MECH, pilotingMod);
							break;
						case Person.T_PROTO_PILOT:
							adjustSkill(p, SkillType.S_GUN_PROTO, gunneryMod);
							break;
						}
						int nabil = Math.max(0, p.getExperienceLevel(false) - SkillType.EXP_REGULAR);
						while (nabil > 0 && null != c.rollSPA(p.getPrimaryRole(), p)) {
							nabil--;
						}
					}
					updated = true;
				}

				break;
			}
		}

		if (updated && c.getCampaignOptions().getPersonnelMarketReportRefresh()) {
			c.addReport("<a href='PERSONNEL_MARKET'>Personnel market updated</a>");
		}
	}

    /*
     * Remove personnel from market on a new day
     * The better they are, the faster they disappear
     */
    public void removePersonnelForDay(Campaign c) {
        List<Person> toRemove = new ArrayList<Person>();
        if (null != method) {
            toRemove = method.removePersonnelForDay(c, personnel);
        } else {
            switch (c.getCampaignOptions().getPersonnelMarketType()) {
                case TYPE_ATB:
                	if (c.getCalendar().get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY) {
                		personnel.clear();
                        attachedEntities.clear();
                	}
                	break;
            }
        }
        if (null != toRemove) {
            for (Person p : toRemove) {
            	if (attachedEntities.contains(p.getId())) {
            		attachedEntities.remove(p.getId());
            	}
            }
            personnel.removeAll(toRemove);
        }
    }

    public void setPersonnel(ArrayList<Person> p) {
        personnel = p;
    }

    public ArrayList<Person> getPersonnel() {
        return personnel;
    }

    public void addPerson(Person p) {
		UUID id = UUID.randomUUID();
		while (null != personnelIds.get(id)) {
			id = UUID.randomUUID();
		}
		p.setId(id);
        personnel.add(p);
    }

    public void addPerson(Person p, Entity e) {
    	addPerson(p);
    	attachedEntities.put(p.getId(), e);
    }

    public void removePerson(Person p) {
        personnel.remove(p);
        if (attachedEntities.containsKey(p.getId())) {
        	attachedEntities.remove(p.getId());
        }
    }

    public Entity getAttachedEntity(Person p) {
    	return attachedEntities.get(p.getId());
    }

    public Entity getAttachedEntity(UUID pid) {
    	return attachedEntities.get(pid);
    }

    public void removeAttachedEntity(UUID id) {
    	attachedEntities.remove(id);
    }

    public boolean getPaidRecruitment() {
    	return paidRecruitment;
    }

    public void setPaidRecruitment(boolean pr) {
    	paidRecruitment = pr;
    }

    public int getPaidRecruitType() {
    	return paidRecruitType;
    }

    public void setPaidRecruitType(int pr) {
    	paidRecruitType = pr;
    }

    public void writeToXml(PrintWriter pw1, int indent) {
        pw1.println(MekHqXmlUtil.indentStr(indent) + "<personnelMarket>");
        for (Person p : personnel) {
            p.writeToXml(pw1, indent + 1);
        }
        if (null != method) {
            method.writeToXml(pw1, indent);
        }
        if (paidRecruitment) {
        	pw1.println(MekHqXmlUtil.indentStr(indent + 1) + "<paidRecruitment/>");
        }
        MekHqXmlUtil.writeSimpleXmlTag(pw1, indent + 1, "paidRecruitType", paidRecruitType);
        
        for (UUID id : attachedEntities.keySet()) {
            pw1.println(MekHqXmlUtil.indentStr(indent + 1)
                    + "<entity id=\"" + id.toString() + "\">"
                    + attachedEntities.get(id).getShortNameRaw()
                    + "</entity>");
        }
        pw1.println(MekHqXmlUtil.indentStr(indent) + "</personnelMarket>");
    }

    public static PersonnelMarket generateInstanceFromXML(Node wn, Campaign c, Version version) {
        final String METHOD_NAME = "generateInstanceFromXML(Node,Campaign,Version)"; //$NON-NLS-1$
        
        PersonnelMarket retVal = null;

        try {
            // Instantiate the correct child class, and call its parsing function.
            retVal = new PersonnelMarket();
            retVal.setType(c.getCampaignOptions().getPersonnelMarketType());

            // Okay, now load Part-specific fields!
            NodeList nl = wn.getChildNodes();

            // Loop through the nodes and load our personnel
            for (int x = 0; x < nl.getLength(); x++) {
            	Node wn2 = nl.item(x);

            	// If it's not an element node, we ignore it.
            	if (wn2.getNodeType() != Node.ELEMENT_NODE) {
            		continue;
            	}
            	
            	if (wn2.getNodeName().equalsIgnoreCase("person")) {
            		Person p = Person.generateInstanceFromXML(wn2, c, version);

            		if (p != null) {
            			retVal.personnel.add(p);
               		}
            	} else if (wn2.getNodeName().equalsIgnoreCase("entity")) {
                    UUID id = UUID.fromString(wn2.getAttributes().getNamedItem("id").getTextContent());
                    MechSummary ms = MechSummaryCache.getInstance().getMech(wn2.getTextContent());
                    Entity en = null;
        			try {
        				en = new MechFileParser(ms.getSourceFile(), ms.getEntryName()).getEntity();
        			} catch (EntityLoadingException ex) {
        	            en = null;
                        MekHQ.getLogger().log(PersonnelMarket.class, METHOD_NAME, LogLevel.ERROR,
                                "Unable to load entity: " + ms.getSourceFile() + ": " //$NON-NLS-1$
                                        + ms.getEntryName() + ": " + ex.getMessage()); //$NON-NLS-1$
                        MekHQ.getLogger().log(PersonnelMarket.class, METHOD_NAME, ex);
        			}
                    if (null != en) {
                    	retVal.attachedEntities.put(id, en);
                    }
            	} else if (wn2.getNodeName().equalsIgnoreCase("paidRecruitment")) {
            		retVal.paidRecruitment = true;
            	} else if (wn2.getNodeName().equalsIgnoreCase("paidRecruitType")) {
            		retVal.paidRecruitType = Integer.parseInt(wn2.getTextContent());
            	} else if (null != retVal.method) {
            	    retVal.method.loadFieldsFromXml(wn2);
          		} else  {
            		// Error condition of sorts!
            		// Errr, what should we do here?
                    MekHQ.getLogger().log(PersonnelMarket.class, METHOD_NAME, LogLevel.ERROR,
                            "Unknown node type not loaded in Personnel nodes: " //$NON-NLS-1$
            				+ wn2.getNodeName());

            	}

            }
        } catch (Exception ex) {
        	// Errrr, apparently either the class name was invalid...
        	// Or the listed name doesn't exist.
        	// Doh!
            MekHQ.getLogger().log(PersonnelMarket.class, METHOD_NAME, ex);
        }

        // All personnel need the rank reference fixed
        for (int x = 0; x < retVal.personnel.size(); x++) {
        	Person psn = retVal.personnel.get(x);

        	// skill types might need resetting
        	psn.resetSkillTypes();
        }

        return retVal;
    }

    public static String getTypeName(int type) {
    	switch (type) {
    	case TYPE_RANDOM:
    		return "Random";
    	case TYPE_DYLANS:
                return "Dylan's Method";
            case TYPE_FMMR:
                return "FM: Mercenaries Revised";
            case TYPE_STRAT_OPS:
                return "Strat Ops";
            case TYPE_ATB:
            	return "Against the Bot";
            default:
                return "ERROR: Default case reached in PersonnelMarket.getTypeName()";
        }
    }
    

    public static long getUnitMainForceType(Campaign c) {
        long mostTypes = getUnitMainForceTypes(c);
        if ((mostTypes & Entity.ETYPE_MECH) != 0) {
            return Entity.ETYPE_MECH;
        } else if ((mostTypes & Entity.ETYPE_TANK) != 0) {
            return Entity.ETYPE_TANK;
        } else if ((mostTypes & Entity.ETYPE_AERO) != 0) {
            return Entity.ETYPE_AERO;
        } else if ((mostTypes & Entity.ETYPE_BATTLEARMOR) != 0) {
            return Entity.ETYPE_BATTLEARMOR;
        } else if ((mostTypes & Entity.ETYPE_INFANTRY) != 0) {
            return Entity.ETYPE_INFANTRY;
        } else if ((mostTypes & Entity.ETYPE_PROTOMECH) != 0) {
            return Entity.ETYPE_PROTOMECH;
        } else if ((mostTypes & Entity.ETYPE_CONV_FIGHTER) != 0) {
            return Entity.ETYPE_CONV_FIGHTER;
        } else if ((mostTypes & Entity.ETYPE_SMALL_CRAFT) != 0) {
            return Entity.ETYPE_SMALL_CRAFT;
        } else if ((mostTypes & Entity.ETYPE_DROPSHIP) != 0) {
            return Entity.ETYPE_DROPSHIP;
        } else {
            return Entity.ETYPE_MECH;
        }
    }

    public static long getUnitMainForceTypes(Campaign c) {
        int mechs = c.getNumberOfUnitsByType(Entity.ETYPE_MECH);
        int ds = c.getNumberOfUnitsByType(Entity.ETYPE_DROPSHIP);
        int sc = c.getNumberOfUnitsByType(Entity.ETYPE_SMALL_CRAFT);
        int cf = c.getNumberOfUnitsByType(Entity.ETYPE_CONV_FIGHTER);
        int asf = c.getNumberOfUnitsByType(Entity.ETYPE_AERO);
        int vee = c.getNumberOfUnitsByType(Entity.ETYPE_TANK, true) + c.getNumberOfUnitsByType(Entity.ETYPE_TANK);
        int inf = c.getNumberOfUnitsByType(Entity.ETYPE_INFANTRY);
        int ba = c.getNumberOfUnitsByType(Entity.ETYPE_BATTLEARMOR);
        int proto = c.getNumberOfUnitsByType(Entity.ETYPE_PROTOMECH);
        int most = mechs;
        if (ds > most) {
            most = ds;
        }
        if (sc > most) {
            most = sc;
        }
        if (cf > most) {
            most = cf;
        }
        if (asf > most) {
            most = asf;
        }
        if (vee > most) {
            most = vee;
        }
        if (inf > most) {
            most = inf;
        }
        if (ba > most) {
            most = ba;
        }
        if (proto > most) {
            most = proto;
        }
        long retval = 0;
        if (most == mechs) {
            retval = retval | Entity.ETYPE_MECH;
        }
        if (most == ds) {
            retval = retval | Entity.ETYPE_DROPSHIP;
        }
        if (most == sc) {
            retval = retval | Entity.ETYPE_SMALL_CRAFT;
        }
        if (most == cf) {
            retval = retval | Entity.ETYPE_CONV_FIGHTER;
        }
        if (most == asf) {
            retval = retval | Entity.ETYPE_AERO;
        }
        if (most == vee) {
            retval = retval | Entity.ETYPE_TANK;
        }
        if (most == inf) {
            retval = retval | Entity.ETYPE_INFANTRY;
        }
        if (most == ba) {
            retval = retval | Entity.ETYPE_BATTLEARMOR;
        }
        if (most == proto) {
            retval = retval | Entity.ETYPE_PROTOMECH;
        }
        return retval;
    }

    private void doPaidRecruitment(Campaign c) {
    	int mod;
    	switch (paidRecruitType) {
    	case Person.T_MECHWARRIOR:
    		mod = -2;
    		break;
    	case Person.T_INFANTRY:
    		mod = 2;
    		break;
    	case Person.T_MECH_TECH:
    	case Person.T_AERO_TECH:
    	case Person.T_MECHANIC:
    	case Person.T_BA_TECH:
    	case Person.T_DOCTOR:
    		mod = 1;
    		break;
    	default:
    		mod = 0;
    	}

    	mod += c.getUnitRatingMod() - IUnitRating.DRAGOON_C;
		if (c.getFinances().isInDebt()) {
			mod -= 3;
		}

		Person adminHR = c.findBestInRole(Person.T_ADMIN_HR, SkillType.S_ADMIN);
		int adminHRExp = (adminHR == null)?SkillType.EXP_ULTRA_GREEN:adminHR.getSkill(SkillType.S_ADMIN).getExperienceLevel();
		mod += adminHRExp - 2;
		int q = 0;
		int r = Compute.d6(2) + mod;
		if (r > 15) {
			q = 6;
		} else if (r > 12) {
			q = 5;
		} else if (r > 10) {
			q = 4;
		} else if (r > 8) {
			q = 3;
		} else if (r > 5) {
			q = 2;
		} else if (r > 3) {
			q = 1;
		}
		for (int i = 0; i < q; i++) {
            Person p = c.newPerson(paidRecruitType);
            UUID id = UUID.randomUUID();
            while (null != personnelIds.get(id)) {
                id = UUID.randomUUID();
            }
            p.setId(id);
            personnel.add(p);
            personnelIds.put(id, p);
            if (c.getCampaignOptions().getUseAtB()) {
            	addRecruitUnit(p, c);
            }
		}
    }
    
    public TargetRoll getShipSearchTarget(Campaign campaign, boolean jumpship) {
    	TargetRoll target = new TargetRoll(jumpship?12:10, "Base");
		Person adminLog = campaign.findBestInRole(Person.T_ADMIN_LOG, SkillType.S_ADMIN);
		int adminLogExp = (adminLog == null)?SkillType.EXP_ULTRA_GREEN:adminLog.getSkill(SkillType.S_ADMIN).getExperienceLevel();
    	for (Person p : campaign.getAdmins()) {
			if ((p.getPrimaryRole() == Person.T_ADMIN_LOG ||
					p.getSecondaryRole() == Person.T_ADMIN_LOG) &&
					p.getSkill(SkillType.S_ADMIN).getExperienceLevel() > adminLogExp) {
				adminLogExp = p.getSkill(SkillType.S_ADMIN).getExperienceLevel();
			}
    	}
    	target.addModifier(SkillType.EXP_REGULAR - adminLogExp, "Admin/Logistics");
    	target.addModifier(IUnitRating.DRAGOON_C - campaign.getUnitRatingMod(),
    			"Unit Rating");
    	return target;
    }

    private void addRecruitUnit(Person p, Campaign c) {
        final String METHOD_NAME = "addRecruitUnit(Person,Campaign)"; //$NON-NLS-1$
        
    	int unitType;
    	switch (p.getPrimaryRole()) {
    	case Person.T_MECHWARRIOR:
    		unitType = UnitType.MEK;
    		break;
    	case Person.T_GVEE_DRIVER:
    	case Person.T_VEE_GUNNER:
    	case Person.T_VTOL_PILOT:
    		return;
     	case Person.T_AERO_PILOT:
    		if (!c.getCampaignOptions().getAeroRecruitsHaveUnits()) {
    			return;
    		}
    		unitType = UnitType.AERO;
    		break;
    	case Person.T_INFANTRY:
    		unitType = UnitType.INFANTRY;
    		break;
    	case Person.T_BA:
    		unitType = UnitType.BATTLE_ARMOR;
    		break;
    	case Person.T_PROTO_PILOT:
    		unitType = UnitType.PROTOMEK;
    		break;
    	default:
    		return;
    	}

    	int weight = -1;
    	if (unitType == UnitType.MEK
    			|| unitType == UnitType.TANK
    			|| unitType == UnitType.AERO) {
			int roll = Compute.d6(2);
	    	if (roll < 8) {
	    		return;
	    	}
	    	if (roll < 10) {
	    		weight = EntityWeightClass.WEIGHT_LIGHT;
	    	} else if (roll < 12) {
	    		weight = EntityWeightClass.WEIGHT_MEDIUM;
	    	} else {
	    		weight = EntityWeightClass.WEIGHT_HEAVY;
	    	}
    	}
    	Entity en = null;

    	String faction = getRecruitFaction(c);
		MechSummary ms = c.getUnitGenerator().generate(faction, unitType, weight, c.getCalendar().get(Calendar.YEAR), IUnitRating.DRAGOON_F);
    	if (null != ms) {
    		if (Faction.getFaction(faction).isClan() && ms.getName().matches(".*Platoon.*")) {
				String name = "Clan " + ms.getName().replaceAll("Platoon", "Point");
				ms = MechSummaryCache.getInstance().getMech(name);
				System.out.println("looking for Clan infantry " + name);
			}
			try {
				en = new MechFileParser(ms.getSourceFile(), ms.getEntryName()).getEntity();
			} catch (EntityLoadingException ex) {
	            en = null;
                MekHQ.getLogger().log(getClass(), METHOD_NAME, LogLevel.ERROR,
                        "Unable to load entity: " + ms.getSourceFile() + ": " //$NON-NLS-1$
                                + ms.getEntryName() + ": " + ex.getMessage()); //$NON-NLS-1$
                MekHQ.getLogger().log(getClass(), METHOD_NAME, ex);
			}
		} else {
            MekHQ.getLogger().log(getClass(), METHOD_NAME, LogLevel.ERROR,
                    "Personnel market could not find " //$NON-NLS-1$
					+ UnitType.getTypeName(unitType) + " for recruit from faction " + faction); //$NON-NLS-1$
			return;
		}

		if (null != en) {
			attachedEntities.put(p.getId(), en);
			/* adjust vehicle pilot roles according to the type of vehicle rolled */
			if ((en.getEntityType() & Entity.ETYPE_TANK) != 0) {
				if (en.getMovementMode() == EntityMovementMode.TRACKED ||
						en.getMovementMode() == EntityMovementMode.WHEELED ||
						en.getMovementMode() == EntityMovementMode.HOVER ||
						en.getMovementMode() == EntityMovementMode.WIGE) {
					if (p.getPrimaryRole() == Person.T_VTOL_PILOT) {
						swapSkills(p, SkillType.S_PILOT_VTOL, SkillType.S_PILOT_GVEE);
						p.setPrimaryRole(Person.T_GVEE_DRIVER);
					}
					if (p.getPrimaryRole() == Person.T_NVEE_DRIVER) {
						swapSkills(p, SkillType.S_PILOT_NVEE, SkillType.S_PILOT_GVEE);
						p.setPrimaryRole(Person.T_GVEE_DRIVER);
					}
				} else if (en.getMovementMode() == EntityMovementMode.VTOL) {
					if (p.getPrimaryRole() == Person.T_GVEE_DRIVER) {
						swapSkills(p, SkillType.S_PILOT_GVEE, SkillType.S_PILOT_VTOL);
						p.setPrimaryRole(Person.T_VTOL_PILOT);
					}
					if (p.getPrimaryRole() == Person.T_NVEE_DRIVER) {
						swapSkills(p, SkillType.S_PILOT_NVEE, SkillType.S_PILOT_VTOL);
						p.setPrimaryRole(Person.T_VTOL_PILOT);
					}
				} else if (en.getMovementMode() == EntityMovementMode.NAVAL ||
						en.getMovementMode() == EntityMovementMode.HYDROFOIL ||
						en.getMovementMode() == EntityMovementMode.SUBMARINE) {
					if (p.getPrimaryRole() == Person.T_GVEE_DRIVER) {
						swapSkills(p, SkillType.S_PILOT_GVEE, SkillType.S_PILOT_NVEE);
						p.setPrimaryRole(Person.T_NVEE_DRIVER);
					}
					if (p.getPrimaryRole() == Person.T_VTOL_PILOT) {
						swapSkills(p, SkillType.S_PILOT_VTOL, SkillType.S_PILOT_NVEE);
						p.setPrimaryRole(Person.T_NVEE_DRIVER);
					}
				}
			}
		}
    }

    private void swapSkills(Person p, String skill1, String skill2) {
    	int s1 = p.hasSkill(skill1)?p.getSkill(skill1).getLevel():0;
    	int b1 = p.hasSkill(skill1)?p.getSkill(skill1).getBonus():0;
    	int s2 = p.hasSkill(skill2)?p.getSkill(skill2).getLevel():0;
    	int b2 = p.hasSkill(skill2)?p.getSkill(skill2).getBonus():0;
    	p.addSkill(skill1, s2, b2);
    	p.addSkill(skill2, s1, b1);
    	if (p.getSkill(skill1).getLevel() == 0) {
    		p.removeSkill(skill1);
    	}
    	if (p.getSkill(skill2).getLevel() == 0) {
    		p.removeSkill(skill2);
    	}
    }

    public void adjustSkill (Person p, String skillName, int mod) {
    	if (p.getSkill(skillName) == null) {
    		return;
    	}
    	if (mod > 0) {
    		p.improveSkill(skillName);
    	}
    	if (mod < 0) {
    		int lvl = p.getSkill(skillName).getLevel() + mod;
    		p.getSkill(skillName).setLevel(Math.max(lvl, 0));
    	}
    }

    public static String getRecruitFaction(Campaign c) {
        if (c.getFactionCode().equals("MERC")) {
        	if (c.getCalendar().get(Calendar.YEAR) > 3055 && Compute.randomInt(20) == 0) {
        		ArrayList<String> clans = new ArrayList<String>();
        		for (String f : RandomFactionGenerator.getInstance().getCurrentFactions()) {
        			if (Faction.getFaction(f).isClan()) {
        				clans.add(f);
        			}
        		}
        		String clan = Utilities.getRandomItem(clans);
        		if (clan != null) {
        		    return clan;
        		}
        	} else {
        	    String faction = RandomFactionGenerator.getInstance().getEmployer();
        	    if (faction != null) {
        	        return faction;
        	    }
        	}
        }
        return c.getFactionCode();
    }
}
