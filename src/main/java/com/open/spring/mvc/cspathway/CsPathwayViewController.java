package com.open.spring.mvc.cspathway;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Teacher/admin page: every student's score and progress for each CS Pathway level.
 * Access is restricted in MvcSecurityConfig.
 */
@Controller
@RequestMapping("/mvc/cs-pathway")
public class CsPathwayViewController {
    private final CsPathwayScoreService scoreService;

    public CsPathwayViewController(CsPathwayScoreService scoreService) {
        this.scoreService = scoreService;
    }

    @GetMapping("/read")
    public String read(Model model) {
        model.addAttribute("levels", CsPathwayLevel.values());
        model.addAttribute("rows", scoreService.getStudentRows());
        return "cs-pathway/read";
    }
}
