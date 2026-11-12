-- Self-service deletion: the API verifies the password before calling this.
-- Never expose this function as a public endpoint or accept an arbitrary user ID.
-- SECURITY INVOKER and explicit schema keep permissions scoped to the DB service.
CREATE OR REPLACE FUNCTION public.finflow_delete_account(p_user_id BIGINT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS '
BEGIN
    IF p_user_id IS NULL THEN
        RETURN FALSE;
    END IF;
    PERFORM 1 FROM public.users WHERE id = p_user_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    -- Remove dependents before their owner/category/account is deleted.
    DELETE FROM public.transactions_log WHERE user_id = p_user_id;
    DELETE FROM public.goal_contributions WHERE user_id = p_user_id;
    DELETE FROM public.recurring_executions WHERE user_id = p_user_id;
    DELETE FROM public.planned_expenses WHERE user_id = p_user_id;
    DELETE FROM public.account_transfers WHERE user_id = p_user_id;
    DELETE FROM public.transactions WHERE user_id = p_user_id;
    DELETE FROM public.recurring_rules WHERE user_id = p_user_id;
    DELETE FROM public.budgets WHERE user_id = p_user_id;
    DELETE FROM public.monthly_budgets WHERE user_id = p_user_id;
    DELETE FROM public.financial_accounts WHERE user_id = p_user_id;
    DELETE FROM public.goals WHERE user_id = p_user_id;
    -- Global categories (user_id IS NULL) must never be deleted.
    DELETE FROM public.categories WHERE user_id = p_user_id;

    -- Remaining per-user tables have ON DELETE CASCADE; global admin/billing
    -- audit references use SET NULL and remain unrelated to the erased user.
    DELETE FROM public.users WHERE id = p_user_id;
    RETURN FOUND;
END
';

REVOKE ALL ON FUNCTION public.finflow_delete_account(BIGINT) FROM PUBLIC;
