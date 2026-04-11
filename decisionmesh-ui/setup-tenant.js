// ============================================================
// routes/setup-tenant.js
// POST /api/setup-tenant
// Called ONCE after first Keycloak login — sets up tenant context
// ============================================================

const express = require("express");
const axios   = require("axios");
const router  = express.Router();

const KEYCLOAK_BASE_URL = process.env.KEYCLOAK_BASE_URL;
const REALM             = process.env.KEYCLOAK_REALM;
const ADMIN_CLIENT_ID   = process.env.KEYCLOAK_ADMIN_CLIENT_ID;
const ADMIN_CLIENT_SECRET = process.env.KEYCLOAK_ADMIN_CLIENT_SECRET;

// ─── MIDDLEWARE: verify JWT from Keycloak ────────────────────
// Assumes you have keycloak-connect or jwt middleware already
// req.user = decoded JWT payload { sub, email, ... }

// ─── HELPERS ─────────────────────────────────────────────────

async function getAdminToken() {
  const res = await axios.post(
    `${KEYCLOAK_BASE_URL}/realms/master/protocol/openid-connect/token`,
    new URLSearchParams({
      grant_type:    "client_credentials",
      client_id:     ADMIN_CLIENT_ID,
      client_secret: ADMIN_CLIENT_SECRET,
    }),
    { headers: { "Content-Type": "application/x-www-form-urlencoded" } }
  );
  return res.data.access_token;
}

async function createKeycloakGroup(adminToken, groupName) {
  const res = await axios.post(
    `${KEYCLOAK_BASE_URL}/admin/realms/${REALM}/groups`,
    { name: groupName },
    { headers: { Authorization: `Bearer ${adminToken}`, "Content-Type": "application/json" } }
  );
  // Group ID is in the Location header
  return res.headers["location"].split("/").pop();
}

async function assignUserToGroup(adminToken, userId, groupId) {
  await axios.put(
    `${KEYCLOAK_BASE_URL}/admin/realms/${REALM}/users/${userId}/groups/${groupId}`,
    {},
    { headers: { Authorization: `Bearer ${adminToken}` } }
  );
}

async function setUserAttributes(adminToken, userId, attributes) {
  // Fetch existing user first (to avoid overwriting other attributes)
  const userRes = await axios.get(
    `${KEYCLOAK_BASE_URL}/admin/realms/${REALM}/users/${userId}`,
    { headers: { Authorization: `Bearer ${adminToken}` } }
  );

  const existing = userRes.data.attributes || {};

  await axios.put(
    `${KEYCLOAK_BASE_URL}/admin/realms/${REALM}/users/${userId}`,
    {
      ...userRes.data,
      attributes: {
        ...existing,
        ...attributes, // tenantId, accountType, etc.
      },
    },
    { headers: { Authorization: `Bearer ${adminToken}`, "Content-Type": "application/json" } }
  );
}

// ─── MAIN ROUTE: POST /api/setup-tenant ──────────────────────
router.post("/setup-tenant", async (req, res) => {
  const { accountType, companyName, companySize } = req.body;
  const userId = req.user.sub; // from decoded Keycloak JWT
  const email  = req.user.email;

  // ── Validation ──────────────────────────────────────────────
  if (!["INDIVIDUAL", "ORGANIZATION"].includes(accountType)) {
    return res.status(400).json({ message: "Invalid accountType" });
  }

  if (accountType === "ORGANIZATION" && !companyName?.trim()) {
    return res.status(400).json({ message: "companyName is required for organizations" });
  }

  // ── Check: already set up? ───────────────────────────────────
  const existingTenant = await db.tenant.findOne({ where: { ownerId: userId } });
  if (existingTenant) {
    return res.status(409).json({ message: "Tenant already set up for this user" });
  }

  try {
    const adminToken = await getAdminToken();
    let tenantId;

    // ════════════════════════════════════════════════════════
    // INDIVIDUAL FLOW
    // ════════════════════════════════════════════════════════
    if (accountType === "INDIVIDUAL") {
      tenantId = userId; // userId IS the tenantId — no group needed

      // Set user attributes in Keycloak
      await setUserAttributes(adminToken, userId, {
        tenantId:    [tenantId],
        accountType: ["INDIVIDUAL"],
      });

      // Save to DB
      await db.tenant.create({
        data: {
          id:      tenantId,
          type:    "INDIVIDUAL",
          name:    email,          // or firstName from JWT
          ownerId: userId,
        },
      });

      await db.user.upsert({
        where:  { id: userId },
        update: { tenantId, role: "OWNER" },
        create: { id: userId, email, tenantId, role: "OWNER" },
      });

      // Create billing (free tier by default)
      await db.billing.create({
        data: {
          tenantId,
          plan:  "FREE",
          seats: 1,
        },
      });

      console.log(`✅ Individual tenant set up: ${userId}`);
    }

    // ════════════════════════════════════════════════════════
    // ORGANIZATION FLOW
    // ════════════════════════════════════════════════════════
    else if (accountType === "ORGANIZATION") {
      // 1. Create Keycloak group → groupId = tenantId
      tenantId = await createKeycloakGroup(adminToken, companyName.trim());

      // 2. Assign user (owner) to the group
      await assignUserToGroup(adminToken, userId, tenantId);

      // 3. Set user attributes in Keycloak
      await setUserAttributes(adminToken, userId, {
        tenantId:    [tenantId],
        accountType: ["ORGANIZATION"],
        role:        ["OWNER"],
      });

      // 4. Save tenant to DB
      await db.tenant.create({
        data: {
          id:          tenantId,      // ← Keycloak group ID
          type:        "ORGANIZATION",
          name:        companyName.trim(),
          companySize: companySize || null,
          ownerId:     userId,
        },
      });

      // 5. Save user to DB
      await db.user.upsert({
        where:  { id: userId },
        update: { tenantId, role: "OWNER" },
        create: { id: userId, email, tenantId, role: "OWNER" },
      });

      // 6. Create billing (1 seat by default)
      await db.billing.create({
        data: {
          tenantId,
          plan:  "FREE",
          seats: 1,           // increases as more users join
        },
      });

      console.log(`✅ Org tenant set up: ${companyName} → tenantId: ${tenantId}`);
    }

    // ── Response ────────────────────────────────────────────
    return res.status(201).json({
      message:     "Tenant setup complete",
      tenantId,
      accountType,
      // Tell frontend to re-fetch token so JWT has tenantId
      requiresTokenRefresh: true,
    });

  } catch (err) {
    console.error(" setup-tenant error:", err.response?.data || err.message);

    // Rollback Keycloak group if DB failed (org only)
    // if (tenantId && accountType === "ORGANIZATION") {
    //   await deleteKeycloakGroup(adminToken, tenantId);
    // }

    return res.status(500).json({ message: "Failed to set up tenant", error: err.message });
  }
});

module.exports = router;


// ============================================================
// middleware/check-onboarding.js
// Add this to your app middleware — redirects to /onboarding
// if user hasn't completed tenant setup yet
// ============================================================

async function checkOnboarding(req, res, next) {
  // Skip for public routes
  const skipRoutes = ["/onboarding", "/api/setup-tenant", "/health"];
  if (skipRoutes.some(r => req.path.startsWith(r))) return next();

  const tenantId = req.user?.tenantId; // from decoded JWT

  if (!tenantId) {
    // User logged in but hasn't set up tenant yet
    return res.redirect("/onboarding");
  }

  next();
}

module.exports = { checkOnboarding };


// ============================================================
// How to wire it all together in app.js
// ============================================================

// const { checkOnboarding } = require('./middleware/check-onboarding');
// const setupTenantRoute    = require('./routes/setup-tenant');
//
// app.use('/api', authenticateJWT);     // verify Keycloak token
// app.use(checkOnboarding);             // redirect if no tenantId
// app.use('/api', setupTenantRoute);    // handle POST /api/setup-tenant
