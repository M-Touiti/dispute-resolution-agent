package dev.disputeagent.investigator.application.port.skills;

import java.util.List;

/** The skills available to the investigator, read once at start-up. */
public interface SkillLibrary {

    /** Every skill, in any order; names are unique. */
    List<Skill> skills();
}
