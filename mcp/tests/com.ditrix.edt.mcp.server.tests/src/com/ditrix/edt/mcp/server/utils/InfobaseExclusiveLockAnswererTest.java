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
    private static final String TERMINATE_RETRY_RU =
        "\u0417\u0430\u0432\u0435\u0440\u0448\u0438\u0442\u044C \u0441\u0435\u0430\u043D\u0441\u044B \u0438 \u043F\u043E\u0432\u0442\u043E\u0440\u0438\u0442\u044C";
    private static final String TERMINATE_RETRY_EN = "Terminate sessions and retry";
    private static final String RETRY_EN = "Retry";
    private static final String CANCEL_EN = "Cancel";

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

    private IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer terminateRetry()
    {
        return new IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer(TERMINATE_RETRY_RU, TERMINATE_RETRY_RU, false);
    }
}
