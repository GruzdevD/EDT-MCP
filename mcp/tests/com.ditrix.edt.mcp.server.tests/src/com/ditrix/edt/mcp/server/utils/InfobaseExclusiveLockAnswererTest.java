/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchonizationQuestionHandler;

/**
 * Tests for {@link InfobaseExclusiveLockAnswerer}.
 * <p>
 * The answerer only acts on the exclusive-infobase-lock question (RU/EN marker in the message):
 * it returns the "terminate sessions and retry" answer so EDT itself ends the sessions and
 * restructures the base. Every other question — and a {@code null} context — must yield
 * {@link Optional#empty()} so the regular flow is untouched.
 */
public class InfobaseExclusiveLockAnswererTest
{
    private static final String EXCLUSIVE_LOCK_MESSAGE_RU =
        "\u041E\u0448\u0438\u0431\u043A\u0430 \u0438\u0441\u043A\u043B\u044E\u0447\u0438\u0442\u0435\u043B\u044C\u043D\u043E\u0439 \u0431\u043B\u043E\u043A\u0438\u0440\u043E\u0432\u043A\u0438 \u0438\u043D\u0444\u043E\u0440\u043C\u0430\u0446\u0438\u043E\u043D\u043D\u043E\u0439 \u0431\u0430\u0437\u044B";
    /**
     * The FULL real exclusive-lock message: it contains BOTH the exclusive-lock marker and the
     * phrase "\u0417\u0430\u0432\u0435\u0440\u0448\u0435\u043D\u0438\u0435 \u0441\u0435\u0430\u043D\u0441\u043E\u0432 \u043F\u0440\u0438\u0432\u0435\u0434\u0435\u0442" that also starts the session-termination warning.
     * Because the warning marker is the unique suffix "\u0412\u044B\u043F\u043E\u043B\u043D\u0438\u0442\u044C \u0437\u0430\u0432\u0435\u0440\u0448\u0435\u043D\u0438\u0435 \u0441\u0435\u0430\u043D\u0441\u043E\u0432?" and the
     * exclusive-lock question is matched FIRST, this must classify as the exclusive-lock question
     * and be answered with "terminate sessions and retry" \u2014 never as the warning, never empty.
     */
    private static final String EXCLUSIVE_LOCK_MESSAGE_FULL_RU =
        "\u041E\u0448\u0438\u0431\u043A\u0430 \u0438\u0441\u043A\u043B\u044E\u0447\u0438\u0442\u0435\u043B\u044C\u043D\u043E\u0439 \u0431\u043B\u043E\u043A\u0438\u0440\u043E\u0432\u043A\u0438 \u0438\u043D\u0444\u043E\u0440\u043C\u0430\u0446\u0438\u043E\u043D\u043D\u043E\u0439 \u0431\u0430\u0437\u044B. \u0417\u0430\u0432\u0435\u0440\u0448\u0435\u043D\u0438\u0435 \u0441\u0435\u0430\u043D\u0441\u043E\u0432 \u043F\u0440\u0438\u0432\u0435\u0434\u0435\u0442 \u043A \u0430\u0432\u0430\u0440\u0438\u0439\u043D\u043E\u043C\u0443 \u0437\u0430\u0432\u0435\u0440\u0448\u0435\u043D\u0438\u044E \u0440\u0430\u0431\u043E\u0442\u044B \u0432\u0441\u0435\u0445 \u043F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u0442\u0435\u043B\u0435\u0439 \u0438\u043D\u0444\u043E\u0440\u043C\u0430\u0446\u0438\u043E\u043D\u043D\u043E\u0439 \u0431\u0430\u0437\u044B!";
    private static final String TERMINATE_RETRY_RU =
        "\u0417\u0430\u0432\u0435\u0440\u0448\u0438\u0442\u044C \u0441\u0435\u0430\u043D\u0441\u044B \u0438 \u043F\u043E\u0432\u0442\u043E\u0440\u0438\u0442\u044C";
    private static final String TERMINATE_RETRY_EN = "Terminate sessions and retry";
    private static final String RETRY_EN = "Retry";
    private static final String CANCEL_EN = "Cancel";
    private static final String SESSION_TERMINATION_WARNING_MESSAGE_RU =
        "\u0417\u0430\u0432\u0435\u0440\u0448\u0435\u043D\u0438\u0435 \u0441\u0435\u0430\u043D\u0441\u043E\u0432 \u043F\u0440\u0438\u0432\u0435\u0434\u0435\u0442 \u043A \u0430\u0432\u0430\u0440\u0438\u0439\u043D\u043E\u043C\u0443 \u0437\u0430\u0432\u0435\u0440\u0448\u0435\u043D\u0438\u044E \u0440\u0430\u0431\u043E\u0442\u044B \u043F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u0442\u0435\u043B\u0435\u0439!\u0412\u044B\u043F\u043E\u043B\u043D\u0438\u0442\u044C \u0437\u0430\u0432\u0435\u0440\u0448\u0435\u043D\u0438\u0435 \u0441\u0435\u0430\u043D\u0441\u043E\u0432?";
    private static final String TERMINATE_CONTINUE_RU =
        "\u0417\u0430\u0432\u0435\u0440\u0448\u0438\u0442\u044C \u0441\u0435\u0430\u043D\u0441\u044B \u0438 \u043F\u0440\u043E\u0434\u043E\u043B\u0436\u0438\u0442\u044C";
    private static final String TERMINATE_CONTINUE_EN = "Terminate sessions and continue";
    private static final String CANCEL_RU = "\u041E\u0442\u043C\u0435\u043D\u0430";

    private final InfobaseExclusiveLockAnswerer answerer = new InfobaseExclusiveLockAnswerer();

    private IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext context(IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer... answers)
    {
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx =
            mock(IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext.class);
        when(ctx.getAnswers()).thenReturn(Arrays.asList(answers));
        return ctx;
    }

    @Test
    public void nullContextIsIgnored()
    {
        assertFalse(answerer.handleQuestion(null).isPresent());
    }

    @Test
    public void unrelatedQuestionIsIgnored()
    {
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx =
            mock(IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext.class);
        when(ctx.getMessage()).thenReturn("Some other infobase question");
        when(ctx.getAnswers()).thenReturn(Collections.singletonList(
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(CANCEL_EN, CANCEL_EN, false)));
        assertFalse(answerer.handleQuestion(ctx).isPresent());
    }

    @Test
    public void answersRussianExclusiveLockQuestionWithTerminateAndRetry()
    {
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer terminate = terminateRetry();
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer cancel =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(CANCEL_EN, CANCEL_EN, false);
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx = context(cancel, terminate);
        when(ctx.getMessage()).thenReturn(EXCLUSIVE_LOCK_MESSAGE_RU);

        Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> result = answerer.handleQuestion(ctx);
        assertTrue(result.isPresent());
        assertSame(terminate, result.get());
    }

    @Test
    public void fullExclusiveLockMessageContainingWarningPhraseIsAnsweredWithTerminateAndRetry()
    {
        // Regression for 3.0.7: the real exclusive-lock message ALSO contains the phrase
        // "Завершение сеансов приведет ..." that starts the warning. A warning marker built from
        // that phrase (checked first) misclassified this as the warning and left it unanswered.
        // With the exclusive-lock question matched first and the warning marker being the unique
        // "Выполнить завершение сеансов?" suffix, this must be answered with terminate-and-retry.
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer terminate = terminateRetry();
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer cancel =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(CANCEL_RU, CANCEL_RU, false);
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx = context(cancel, terminate);
        when(ctx.getMessage()).thenReturn(EXCLUSIVE_LOCK_MESSAGE_FULL_RU);

        Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> result = answerer.handleQuestion(ctx);
        assertTrue(result.isPresent());
        assertSame(terminate, result.get());
        assertEquals(TERMINATE_RETRY_RU, result.get().getLabel());
    }

    @Test
    public void answersEnglishExclusiveLockQuestionWithTerminateAndRetry()
    {
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer terminate =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(TERMINATE_RETRY_EN, TERMINATE_RETRY_EN, false);
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx = context(terminate);
        when(ctx.getMessage()).thenReturn("Exclusive access to the infobase is not available");

        Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> result = answerer.handleQuestion(ctx);
        assertTrue(result.isPresent());
        assertSame(terminate, result.get());
    }

    @Test
    public void fallsBackToPlainRetryWhenTerminateAnswerAbsent()
    {
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer retry =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(RETRY_EN, RETRY_EN, false);
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer cancel =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(CANCEL_EN, CANCEL_EN, false);
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx = context(cancel, retry);
        when(ctx.getMessage()).thenReturn(EXCLUSIVE_LOCK_MESSAGE_RU);

        Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> result = answerer.handleQuestion(ctx);
        assertTrue(result.isPresent());
        assertSame(retry, result.get());
    }

    @Test
    public void fallsBackToDefaultAnswerWithoutRetryOrTerminate()
    {
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer cancel =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(CANCEL_EN, CANCEL_EN, false);
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer other =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer("other", "Other", true);
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx = context(cancel, other);
        when(ctx.getMessage()).thenReturn(EXCLUSIVE_LOCK_MESSAGE_RU);

        Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> result = answerer.handleQuestion(ctx);
        assertTrue(result.isPresent());
        assertSame(other, result.get());
    }

    @Test
    public void noUsableAnswerYieldsEmptyEvenForExclusiveLockQuestion()
    {
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx = context();
        when(ctx.getMessage()).thenReturn(EXCLUSIVE_LOCK_MESSAGE_RU);

        assertFalse(answerer.handleQuestion(ctx).isPresent());
    }

    @Test
    public void terminateAnswerWinsOverPlainRetryAndDefault()
    {
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer terminate = terminateRetry();
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer retry =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(RETRY_EN, RETRY_EN, true);
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx = context(retry, terminate);
        when(ctx.getMessage()).thenReturn(EXCLUSIVE_LOCK_MESSAGE_RU);

        Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> result = answerer.handleQuestion(ctx);
        assertTrue(result.isPresent());
        assertSame(terminate, result.get());
        assertEquals(TERMINATE_RETRY_RU, result.get().getLabel());
    }

    @Test
    public void answersSessionTerminationWarningWithTerminateAndContinue()
    {
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer cont =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(TERMINATE_CONTINUE_RU, TERMINATE_CONTINUE_RU, false);
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer cancel =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(CANCEL_RU, CANCEL_RU, true);
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx = context(cancel, cont);
        when(ctx.getMessage()).thenReturn(SESSION_TERMINATION_WARNING_MESSAGE_RU);

        Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> result = answerer.handleQuestion(ctx);
        assertTrue(result.isPresent());
        assertSame(cont, result.get());
        assertEquals(TERMINATE_CONTINUE_RU, result.get().getLabel());
    }

    @Test
    public void answersSessionTerminationWarningWithEnglishContinueLabel()
    {
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer cont = terminateContinueEn();
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx = context(cont);
        when(ctx.getMessage()).thenReturn(SESSION_TERMINATION_WARNING_MESSAGE_RU);

        Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> result = answerer.handleQuestion(ctx);
        assertTrue(result.isPresent());
        assertSame(cont, result.get());
    }

    @Test
    public void sessionTerminationWarningWithoutContinueAnswerYieldsEmptyRatherThanCancel()
    {
        // Default is "Cancel"; without the explicit continue answer the handler must refuse,
        // not press the destructive default.
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer cancel =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(CANCEL_RU, CANCEL_RU, true);
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx = context(cancel);
        when(ctx.getMessage()).thenReturn(SESSION_TERMINATION_WARNING_MESSAGE_RU);

        assertFalse(answerer.handleQuestion(ctx).isPresent());
    }

    @Test
    public void exclusiveLockUnderExternalModeAbortsWithCancelAndRecordsSignal()
    {
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer terminate = terminateRetry();
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer cancel =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(CANCEL_RU, CANCEL_RU, false);
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx = context(cancel, terminate);
        when(ctx.getMessage()).thenReturn(EXCLUSIVE_LOCK_MESSAGE_RU);
        try
        {
            StandaloneMonopolisticRestructure.armExternal();
            Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> result =
                answerer.handleQuestion(ctx);
            assertTrue(result.isPresent());
            assertSame("external mode must abort with Cancel, not end sessions", cancel, result.get()); //$NON-NLS-1$
            assertTrue("the monopolistic-restructure signal must be recorded for the aborting tool", //$NON-NLS-1$
                StandaloneMonopolisticRestructure.consumeRestructureRequested());
        }
        finally
        {
            StandaloneMonopolisticRestructure.disarmExternal();
            StandaloneMonopolisticRestructure.consumeRestructureRequested();
        }
    }

    @Test
    public void exclusiveLockUnderExternalModeWithoutCancelYieldsEmptyNotTerminate()
    {
        // No Cancel on offer: the answerer must NOT end sessions under external mode; it leaves the
        // question to the platform (empty) and does NOT claim a restructure was requested.
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer terminate = terminateRetry();
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx = context(terminate);
        when(ctx.getMessage()).thenReturn(EXCLUSIVE_LOCK_MESSAGE_RU);
        try
        {
            StandaloneMonopolisticRestructure.armExternal();
            assertFalse(answerer.handleQuestion(ctx).isPresent());
            assertFalse(StandaloneMonopolisticRestructure.consumeRestructureRequested());
        }
        finally
        {
            StandaloneMonopolisticRestructure.disarmExternal();
            StandaloneMonopolisticRestructure.consumeRestructureRequested();
        }
    }

    @Test
    public void sessionWarningUnderExternalModeAbortsWithCancelInsteadOfContinue()
    {
        // The follow-up warning must NOT be answered "terminate sessions and continue" under
        // external mode — that would end user sessions; Cancel keeps the abort clean.
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer cont =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(TERMINATE_CONTINUE_RU, TERMINATE_CONTINUE_RU, false);
        IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer cancel =
            new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(CANCEL_RU, CANCEL_RU, false);
        IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext ctx = context(cancel, cont);
        when(ctx.getMessage()).thenReturn(SESSION_TERMINATION_WARNING_MESSAGE_RU);
        try
        {
            StandaloneMonopolisticRestructure.armExternal();
            Optional<IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer> result =
                answerer.handleQuestion(ctx);
            assertTrue(result.isPresent());
            assertSame("session warning under external mode must abort with Cancel", cancel, result.get()); //$NON-NLS-1$
        }
        finally
        {
            StandaloneMonopolisticRestructure.disarmExternal();
            StandaloneMonopolisticRestructure.consumeRestructureRequested();
        }
    }

    private IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer terminateRetry()
    {
        return new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(TERMINATE_RETRY_RU, TERMINATE_RETRY_RU, false);
    }

    private IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer terminateContinueEn()
    {
        return new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(TERMINATE_CONTINUE_EN, TERMINATE_CONTINUE_EN, false);
    }
}
