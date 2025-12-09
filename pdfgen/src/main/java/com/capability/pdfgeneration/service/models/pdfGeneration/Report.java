package com.capability.pdfgeneration.service.models.pdfGeneration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Report {
    public final List<String> errors = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final Set<String> variables = new LinkedHashSet<>();
    public final Set<String> sectionExprs = new LinkedHashSet<>();
    public boolean ok() { return errors.isEmpty(); }
}
