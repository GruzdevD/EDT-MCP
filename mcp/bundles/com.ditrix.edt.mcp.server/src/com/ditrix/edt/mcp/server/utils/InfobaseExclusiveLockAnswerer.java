/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.List;
import java.util.Optional;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchonizationQuestionHandler;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Platform infobase-synchronization question handler that answers, the way the interactive EDT
 * does, the two questions EDT asks before terminating user sessions to apply a structural update:
 * the exclusive-infobase-lock question and the follow-up session-termination warning. This lets an
 * unattended (headless) run structurally update a base, including one hosted by a standalone
 * server, instead of aborting with "Delegate provides no answer".
 *
 * <p>When EDT needs to apply a structural (monopolistic) configuration change it asks a
 * {@code DbUpdateQuestionResponse} — "Ошибка исключительной блокировки информационной базы / Завершить
 * сеансы и повторить?", and if EDT itself is to end those sessions it follows with the warning
 * "Завершение сеансов приведет к аварийному завершению работы пользователей! Выполнить завершение
 * сеансов?" (whose default answer is "Cancel"). Because a headless run has no UI to answer them, the
 * platform refuses the update. The fix is not to press a dialog button (there is none on that
 * channel) but to register this handler as the {@link IInfobaseSynchonizationQuestionHandler} OSGi
 * service EDT resolves for infobase-sync questions. We answer the exclusive-lock question with
 * "terminate sessions and retry" and the warning with "terminate sessions and continue"; EDT then
 * ends the sessions itself and restructures the base — exactly as in interactive use. On macOS
 * there is no {@code ibcmd}/{@code ring} binary, so this is the only headless restructure path that
 * works at all.
 *
 * <p>Self-contained and conservative: every other question yields {@link Optional#empty()} so the
 * regular flow (and any other handler EDT consults) is unaffected.
 *
 * <p><b>External mode (3.0.9).</b> Terminating user sessions is destructive on a live standalone
 * server, so {@code update_database} / {@code launch} / {@code run_yaxunit_tests} can opt into the
 * external-monopolistic-restructure path (see {@link StandaloneMonopolisticRestructure}). While that
 * path is armed, the exclusive-lock question is instead answered with "Cancel" — a clean abort of the
 * update that lets the tool stop the server, apply the restructure through an external
 * {@code 1cv8 DESIGNER} offline, and restart it — and the fact that the platform asked for a
 * monopolistic restructure is recorded for the aborting tool ({@code requestRestructure}). The
 * session-termination warning is likewise answered "Cancel" under external mode (its default is
 * already Cancel, so it is effectively left un-answered). If the exclusive-lock question offers no
 * "Cancel" answer, the question is left to the platform rather than prematurely ending sessions.
 */
public final class InfobaseExclusiveLockAnswerer implements IInfobaseSynchonizationQuestionHandler
{
    /**
     * Russian marker of the exclusive-infobase-lock question message. Escaped as {@code \\uXXXX}
     * (CLAUDE.md rule #7: no raw Cyrillic in source).
     */
    private static final String EXCLUSIVE_LOCK_MARKER_RU = //$NON-NLS-1$
        "\u041E\u0448\u0438\u0431\u043A\u0430 \u0438\u0441\u043A\u043B\u044E\u0447\u0438\u0442\u0435\u043B\u044C\u043D\u043E\u0439 \u0431\u043B\u043E\u043A\u0438\u0440\u043E\u0432\u043A\u0438 \u0438\u043D\u0444\u043E\u0440\u043C\u0430\u0446\u0438\u043E\u043D\u043D\u043E\u0439 \u0431\u0430\u0437\u044B";

    /** English marker of the same question. */
    private static final String EXCLUSIVE_LOCK_MARKER_EN = "Exclusive access to the infobase"; //$NON-NLS-1$

    /**
     * RU label of the "terminate sessions and retry" answer. Escaped as {@code \\uXXXX} (rule #7).
     */
    private static final String TERMINATE_RETRY_LABEL_RU = //$NON-NLS-1$
        "\u0417\u0430\u0432\u0435\u0440\u0448\u0438\u0442\u044C \u0441\u0435\u0430\u043D\u0441\u044B \u0438 \u043F\u043E\u0432\u0442\u043E\u0440\u0438\u0442\u044C";

    /** EN label of the same answer. */
    private static final String TERMINATE_RETRY_LABEL_EN = "Terminate sessions and retry"; //$NON-NLS-1$

    /** RU label of the plain "retry" fallback. Escaped as {@code \\uXXXX} (rule #7). */
    private static final String RETRY_LABEL_RU = "\u041F\u043E\u0432\u0442\u043E\u0440\u0438\u0442\u044C"; //$NON-NLS-1$

    /** EN label of the plain "retry" fallback. */
    private static final String RETRY_LABEL_EN = "Retry"; //$NON-NLS-1$

    /**
     * RU marker of the platform's session-termination warning question: "Завершение сеансов
     * приведет к аварийному завершению работы пользователей! Выполнить завершение сеансов?".
     * The marker is the unique question suffix "Выполнить завершение сеансов?" but NOT the phrase
     * "Завершение сеансов приведет": that phrase is ALSO a substring of the exclusive-infobase-lock
     * question (its message ends "... всех пользователей информационной базы!" and never asks
     * "Выполнить завершение сеансов?"). The default of this warning is "Cancel", so it must be
     * answered explicitly. Escaped as {@code \\uXXXX} (CLAUDE.md rule #7: no raw Cyrillic in source).
     */
    private static final String SESSION_TERMINATION_WARNING_MARKER_RU = //$NON-NLS-1$
        "\u0412\u044B\u043F\u043E\u043B\u043D\u0438\u0442\u044C \u0437\u0430\u0432\u0435\u0440\u0448\u0435\u043D\u0438\u0435 \u0441\u0435\u0430\u043D\u0441\u043E\u0432?";

    /** RU label of the "terminate sessions and continue" answer of that warning. */
    private static final String SESSION_TERMINATION_CONTINUE_LABEL_RU = //$NON-NLS-1$
        "\u0417\u0430\u0432\u0435\u0440\u0448\u0438\u0442\u044C \u0441\u0435\u0430\u043D\u0441\u044B \u0438 \u043F\u0440\u043E\u0434\u043E\u043B\u0436\u0438\u0442\u044C";

    /** EN label of the same answer. */
    private static final String SESSION_TERMINATION_CONTINUE_LABEL_EN = //$NON-NLS-1$
        "Terminate sessions and continue";

    /**
     * RU label of the "Cancel" answer, used to abort cleanly under external mode. Escaped as
     * {@code \\uXXXX} (CLAUDE.md rule #7: no raw Cyrillic in source).
     */
    private static final String CANCEL_LABEL_RU = //$NON-NLS-1$
        "\u041E\u0442\u043C\u0435\u043D\u0430";

    /** EN label of the same answer. */
    private static final String CANCEL_LABEL_EN = "Cancel"; //$NON-NLS-1$

    public InfobaseExclusiveLockAnswerer()
    {
        // Default
    }

    /**
     * Answers the two infobase-synchronization questions that EDT asks before it terminates user
     * sessions to apply a structural update: the exclusive-infobase-lock question ("terminate
     * sessions and retry") and the follow-up session-termination warning ("terminate sessions and
     * continue"). The exclusive-lock question is matched FIRST, because its message contains the
     * phrase "Завершение сеансов приведет ..." that also starts the warning's message — reusing
     * that phrase as the warning marker while checking the warning first would misclassify the
     * exclusive-lock question and leave it unanswered. Any other question is left unanswered
     * ({@link Optional#empty()}). Never throws.
     *
     * @param context the question EDT is asking (may be {@code null})
     * @return the chosen answer, or {@link Optional#empty()} when the question is not one this
     *         handler answers
     */
    @Override
    public Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> handleQuestion(
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext context)
    {
        if (context == null)
        {
            return Optional.empty();
        }
        String message = context.getMessage();
        if (isExclusiveLockQuestion(message))
        {
            // Under external mode we WANT this question — it is the reliable "a monopolistic
            // restructure is needed" signal. Abort the update cleanly with "Cancel" instead of
            // ending user sessions, and tell the aborting tool to run the offline restructure.
            if (StandaloneMonopolisticRestructure.isArmedExternal())
            {
                Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> cancel = cancelAnswer(context);
                if (cancel.isPresent())
                {
                    StandaloneMonopolisticRestructure.requestRestructure(message);
                    return cancel;
                }
                // No Cancel on offer (unexpected variant): better to leave it to the platform than
                // to end sessions without the tool knowing it aborted.
                Activator.logWarning("External standalone restructure is armed but the exclusive-lock " //$NON-NLS-1$
                    + "question offered no Cancel answer; leaving it to the platform"); //$NON-NLS-1$
                return Optional.empty();
            }
            Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> chosen = terminateAndRetryAnswer(context);
            if (!chosen.isPresent())
            {
                Activator.logInfo("Exclusive-infobase-lock question seen but no 'terminate and retry'" //$NON-NLS-1$
                    + " answer offered; leaving it to the platform"); //$NON-NLS-1$
            }
            return chosen;
        }
        if (isSessionTerminationWarning(message))
        {
            // Under external mode this follow-up (should EDT already be ending sessions) must NOT be
            // answered "terminate and continue" — that would end user sessions. Cancel keeps the
            // update aborted cleanly, matching the exclusive-lock abort above.
            if (StandaloneMonopolisticRestructure.isArmedExternal())
            {
                Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> cancel = cancelAnswer(context);
                if (cancel.isPresent())
                {
                    return cancel;
                }
                // Its default is already Cancel, so leaving it to the platform is equally safe.
                return Optional.empty();
            }
            Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> continueChoice =
                terminateAndContinueAnswer(context);
            if (!continueChoice.isPresent())
            {
                Activator.logInfo("Session-termination warning seen but no 'terminate sessions and" //$NON-NLS-1$
                    + " continue' answer offered; leaving it to the platform"); //$NON-NLS-1$
            }
            return continueChoice;
        }
        return Optional.empty();
    }

    /**
     * Picks the explicit "Cancel"/"Отмена" answer of a question — the clean abort under external
     * mode. Returns empty when no such label is on offer; it deliberately does NOT fall back to the
     * platform default, because for the exclusive-lock question that default may be "terminate
     * sessions and retry" (the very destructive choice external mode exists to avoid).
     *
     * @param context the question (non-null)
     * @return the Cancel answer, or empty
     */
    private static Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> cancelAnswer(
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext context)
    {
        List<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> answers = context.getAnswers();
        if (answers == null)
        {
            return Optional.empty();
        }
        for (IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer answer : answers)
        {
            if (answer == null)
            {
                continue;
            }
            if (isLabel(answer.getLabel(), CANCEL_LABEL_RU, CANCEL_LABEL_EN))
            {
                return Optional.of(answer);
            }
        }
        return Optional.empty();
    }

    /**
     * Matches the platform's session-termination warning question by its unique question suffix
     * "Завершение сеансов приведет к аварийному завершению работы пользователей! Выполнить
     * завершение сеансов?". The marker used is the "Выполнить завершение сеансов?" suffix, which
     * appears ONLY in this warning's message and not in the exclusive-infobase-lock question whose
     * message also starts with "Завершение сеансов приведет ...". Its default answer is "Cancel",
     * so the handler must answer it with the explicit "terminate sessions and continue" label
     * instead of falling through to the default. Null or unrecognized text is {@code false}.
     *
     * @param message the question message (may be {@code null})
     * @return {@code true} when this is the session-termination warning
     */
    private static boolean isSessionTerminationWarning(String message)
    {
        return message != null && message.contains(SESSION_TERMINATION_WARNING_MARKER_RU);
    }

    /**
     * Picks the explicit "terminate sessions and continue" answer of the session-termination
     * warning. Never the default — that is "Cancel" and would abort the update. Returns empty when
     * no such answer is on offer, so an unrecognized variant is left to the platform rather than
     * answered wrongly.
     *
     * @param context the session-termination warning question (non-null)
     * @return the chosen answer, or empty
     */
    private static Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> terminateAndContinueAnswer(
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext context)
    {
        List<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> answers = context.getAnswers();
        if (answers == null)
        {
            return Optional.empty();
        }
        for (IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer answer : answers)
        {
            if (answer == null)
            {
                continue;
            }
            if (isLabel(answer.getLabel(), SESSION_TERMINATION_CONTINUE_LABEL_RU, SESSION_TERMINATION_CONTINUE_LABEL_EN))
            {
                return Optional.of(answer);
            }
        }
        return Optional.empty();
    }

    /**
     * Matches the exclusive-infobase-lock question by its message text (RU or EN marker). Null or
     * unrecognized text is {@code false}.
     *
     * @param message the question message (may be {@code null})
     * @return {@code true} when this is the exclusive-lock question
     */
    private static boolean isExclusiveLockQuestion(String message)
    {
        return message != null
            && (message.contains(EXCLUSIVE_LOCK_MARKER_RU)
                || message.contains(EXCLUSIVE_LOCK_MARKER_EN));
    }

    /**
     * Picks the answer: prefer the explicit "terminate sessions and retry" label, then the plain
     * "retry", then the platform's default; never "cancel". Returns empty when no usable answer is
     * on offer.
     *
     * @param context the exclusive-lock question (non-null)
     * @return the chosen answer, or empty
     */
    private static Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> terminateAndRetryAnswer(
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext context)
    {
        List<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> answers = context.getAnswers();
        if (answers == null)
        {
            return Optional.empty();
        }
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer retry = null;
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer fallback = null;
        for (IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer answer : answers)
        {
            if (answer == null)
            {
                continue;
            }
            String label = answer.getLabel();
            if (isLabel(label, TERMINATE_RETRY_LABEL_RU, TERMINATE_RETRY_LABEL_EN))
            {
                return Optional.of(answer);
            }
            if (isLabel(label, RETRY_LABEL_RU, RETRY_LABEL_EN) && retry == null)
            {
                retry = answer;
            }
            if (answer.isDefaultAnswer() && fallback == null)
            {
                fallback = answer;
            }
        }
        if (retry != null)
        {
            return Optional.of(retry);
        }
        if (fallback != null)
        {
            return Optional.of(fallback);
        }
        return Optional.empty();
    }

    private static boolean isLabel(String label, String ru, String en)
    {
        if (label == null)
        {
            return false;
        }
        return label.equals(ru) || label.equals(en)
            || label.trim().equalsIgnoreCase(ru) || label.trim().equalsIgnoreCase(en);
    }
}
