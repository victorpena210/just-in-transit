package com.victorpena.just_in_transit;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@ControllerAdvice(assignableTypes={HomeController.class,HistoryController.class})
public class PageErrorHandler {
    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public String databaseUnavailable(DataAccessException error,Model model,jakarta.servlet.http.HttpServletRequest request) {
        org.slf4j.LoggerFactory.getLogger(PageErrorHandler.class).error("Commute data operation failed; reference {}",request.getAttribute("requestReference"),error);
        model.addAttribute("supportCode",request.getAttribute("requestReference"));
        model.addAttribute("errorTitle","Your commute data is temporarily unavailable");
        model.addAttribute("errorHelp","Your last submitted setup is kept in this browser session when possible. Return to your commute before trying again. If you run this app locally, make sure only one copy is using the database.");
        return "error";
    }
    @ExceptionHandler(Exception.class) @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String unexpected(Exception error,Model model,jakarta.servlet.http.HttpServletRequest request) throws Exception {
        if(error instanceof org.springframework.web.server.ResponseStatusException)throw error;
        String reference=String.valueOf(request.getAttribute("requestReference"));
        org.slf4j.LoggerFactory.getLogger(PageErrorHandler.class).error("Page request failed; reference {}",reference,error);
        model.addAttribute("supportCode",reference);return "error";
    }
}
