// Fellah-Market Supabase Edge Function: admin-reset-pin
// Resets a user's PIN after admin phone-call verification, marks must_change_pin=true,
// and logs the event to public.pin_reset_audit.
// Security: Uses SUPABASE_SERVICE_ROLE_KEY strictly server-side; never stores or logs the PIN.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function isStrongPin(pin: string): boolean {
  if (!/^\d{6,12}$/.test(pin)) return false;
  if (new Set(pin.split("")).size <= 1) return false;
  let asc = true;
  let desc = true;
  for (let i = 1; i < pin.length; i++) {
    const diff = Number(pin[i]) - Number(pin[i - 1]);
    if (diff !== 1) asc = false;
    if (diff !== -1) desc = false;
  }
  if (asc || desc) return false;
  return true;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), {
      status: 405,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    if (!supabaseUrl || !serviceRoleKey) {
      return new Response(JSON.stringify({ error: "Server configuration missing" }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const authHeader = req.headers.get("Authorization") ?? "";
    const jwt = authHeader.replace(/^Bearer\s+/i, "").trim();
    if (!jwt) {
      return new Response(JSON.stringify({ error: "Unauthorized" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const supabaseAdmin = createClient(supabaseUrl, serviceRoleKey, {
      auth: { autoRefreshToken: false, persistSession: false },
    });

    // 1. Verify caller identity & admin role
    const { data: userData, error: userErr } = await supabaseAdmin.auth.getUser(jwt);
    if (userErr || !userData?.user) {
      return new Response(JSON.stringify({ error: "Invalid session" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const callerId = userData.user.id;
    const { data: callerProfile, error: profErr } = await supabaseAdmin
      .from("profiles")
      .select("role")
      .eq("id", callerId)
      .single();

    if (profErr || callerProfile?.role !== "admin") {
      return new Response(JSON.stringify({ error: "Forbidden: admin role required" }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // 2. Validate payload without logging PIN or phone
    const body = await req.json();
    const targetUserId = String(body?.target_user_id ?? "").trim();
    const temporaryPin = String(body?.temporary_pin ?? "").trim();

    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (!uuidRegex.test(targetUserId)) {
      return new Response(JSON.stringify({ error: "Invalid target_user_id" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }
    if (!isStrongPin(temporaryPin)) {
      return new Response(JSON.stringify({ error: "Weak temporary PIN (min 6 digits, non-sequential/non-repeated)" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // 3. Update user password via Admin Auth API
    const { error: updateAuthErr } = await supabaseAdmin.auth.admin.updateUserById(targetUserId, {
      password: temporaryPin,
    });
    if (updateAuthErr) {
      return new Response(JSON.stringify({ error: "Failed to reset user PIN" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // 4. Require PIN change at next login
    const { error: flagErr } = await supabaseAdmin
      .from("profiles")
      .update({ must_change_pin: true })
      .eq("id", targetUserId);
    if (flagErr) {
      return new Response(JSON.stringify({ error: "Failed to set must_change_pin flag" }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // 5. Record audit entry (admin_id, target_user_id, created_at only — never store PIN)
    const { error: auditErr } = await supabaseAdmin
      .from("pin_reset_audit")
      .insert({
        admin_id: callerId,
        target_user_id: targetUserId,
      });
    if (auditErr) {
      return new Response(JSON.stringify({ error: "Failed to write audit log" }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    return new Response(
      JSON.stringify({ ok: true, target_user_id: targetUserId, must_change_pin: true }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch {
    return new Response(JSON.stringify({ error: "Internal server error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
