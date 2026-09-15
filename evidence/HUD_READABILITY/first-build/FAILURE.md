The first file-writing helper ran from forge/ with repository-relative paths and
raised FileNotFoundError before updating StressHud or translations. The subsequent
build compiled the new, not-yet-used HudPanel but the existing LangKeysTest failed
on its missing br.hud.more_details key: 107 PASS, 1 FAIL, 1 SKIP. Retain that run;
complete the intended edits and rerun normally. This was an incomplete-edit setup
failure, not a passing HUD candidate or a behavioural mutation test.
