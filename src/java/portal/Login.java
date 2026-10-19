package portal;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@WebServlet("/Login")
public class Login extends HttpServlet {

    private static final String DB_URL
            = "jdbc:mysql://localhost:3306/student_portal";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "";

    private static final String COLLEGE_LOGO
            = "/images/acet-logo.png";

    // Change these to your real portal support details.
    private static final String SUPPORT_EMAIL = "acet.support@acet.com";
    private static final String SUPPORT_HOURS = "Monday to Saturday, 9:10 AM \u2013 4:05 PM";

    /**
     * Picks the photo file for a student. 24R01A6656 keeps his own photo, the
     * students on the login placement banner have fixed photos, and everyone
     * else gets boyN.jpg / girlN.jpg (N = 1..10) chosen from the roll number.
     */
    private String photoFor(String roll, String gender) {
        String r = roll == null ? "" : roll.trim().toUpperCase(java.util.Locale.ROOT);
        if (r.equals("24R01A6656")) {
            return "student-profile.jpg";
        }
        switch (r) {
            case "25R01A66Y3": return "girl4.jpg";
            case "25R01A66T1": return "girl3.jpg";
            case "25R01A66V3": return "boy1.jpg";
            case "25R01A66W9": return "boy2.jpg";
            case "26R05A6633": return "vikram-photo.jpg";
            default: break;
        }
        String g = gender == null ? "" : gender.trim().toLowerCase(java.util.Locale.ROOT);
        int n = ((r.hashCode() & 0x7fffffff) % 10) + 1;
        if (g.equals("female") || g.equals("girl") || g.equals("f")) {
            return "girl" + n + ".jpg";
        }
        if (g.equals("male") || g.equals("boy") || g.equals("m")) {
            return "boy" + n + ".jpg";
        }
        return "classmate-boy.png";
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                DB_URL, DB_USER, DB_PASSWORD);
    }

    @Override
    protected void doGet(HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);

        if ("logout".equals(request.getParameter("action"))) {
            HttpSession session = request.getSession(false);

            if (session != null) {
                session.invalidate();
            }

            response.sendRedirect(
                    request.getContextPath() + "/index.html");
            return;
        }

        HttpSession session = request.getSession(false);

        if (session == null
                || session.getAttribute("studentId") == null) {
            response.sendRedirect(
                    request.getContextPath() + "/index.html");
            return;
        }

        response.setContentType("text/html;charset=UTF-8");

        int studentId = (Integer) session.getAttribute("studentId");

        try (PrintWriter out = response.getWriter()) {
            Class.forName("com.mysql.cj.jdbc.Driver");

            try (Connection con = getConnection(); PreparedStatement ps = con.prepareStatement(
                    "SELECT * FROM students WHERE id = ?")) {

                ps.setInt(1, studentId);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String requestedAction = request.getParameter("action");
                        if ("document".equals(requestedAction)) {
                            String documentType = request.getParameter("type");
                            if (!"marks".equals(documentType)
                                    && !"bonafide".equals(documentType)) {
                                response.sendError(
                                        HttpServletResponse.SC_BAD_REQUEST,
                                        "Unknown document type.");
                                return;
                            }
                            renderStudentDocument(out, rs, documentType,
                                    request.getContextPath());
                        } else {
                            renderDashboard(
                                    out, rs, "", request.getContextPath());
                        }
                    } else {
                        session.invalidate();
                        response.sendRedirect(
                                request.getContextPath() + "/index.html");
                    }
                }
            }
        } catch (ClassNotFoundException | SQLException e) {
            log("Unable to load student dashboard", e);
            response.sendError(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Unable to load student information.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);

        String action = request.getParameter("action");

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");

            if ("changePassword".equals(action)) {
                handlePasswordChange(request, response);
                return;
            }

            String loginId = request.getParameter("username");
            String password = request.getParameter("password");

            if (loginId == null || password == null
                    || loginId.trim().isEmpty()
                    || password.isEmpty()) {
                showMessage(response,
                        "Please enter your name or roll number and your password.",
                        false);
                return;
            }

            String sql
                    = "SELECT * FROM students "
                    + "WHERE (roll_number = ? OR username = ?) "
                    + "AND password = ? LIMIT 1";

            try (Connection con = getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setString(1, loginId.trim());
                ps.setString(2, loginId.trim());
                ps.setString(3, password);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        startSession(request, response, rs);
                    } else {
                        // Not a username or roll number: try the full name
                        // (any capital or small letters, extra spaces ignored).
                        String typedName = loginId.trim().replaceAll("\\s+", " ")
                                .toLowerCase(java.util.Locale.ROOT);
                        loginByFullName(request, response, con, typedName, password);
                    }
                }
            }
        } catch (ClassNotFoundException | SQLException e) {
            log("Login error", e);
            showMessage(response,
                    "Unable to access the database. Check MySQL and "
                    + "the students table.", false);
        }
    }

    private void startSession(HttpServletRequest request,
            HttpServletResponse response, ResultSet rs)
            throws SQLException, IOException {
        int studentId = rs.getInt("id");

        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute("studentId", studentId);
        session.setAttribute("rollNumber", getValue(rs, "roll_number"));
        session.setAttribute("username", getValue(rs, "username"));

        response.sendRedirect(request.getContextPath() + "/Login");
    }

    private void loginByFullName(HttpServletRequest request,
            HttpServletResponse response, Connection con,
            String typedName, String password)
            throws SQLException, IOException {

        String sql = "SELECT * FROM students "
                + "WHERE LOWER(TRIM(full_name)) = ? AND password = ? LIMIT 2";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, typedName);
            ps.setString(2, password);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    showMessage(response,
                            "Incorrect name, roll number or password.", false);
                    return;
                }
                int firstId = rs.getInt("id");
                if (rs.next()) {
                    // Two students share this name and password.
                    showMessage(response,
                            "More than one student has this name. "
                            + "Please sign in with your roll number.", false);
                    return;
                }
                try (PreparedStatement ps2 = con.prepareStatement(
                        "SELECT * FROM students WHERE id = ?")) {
                    ps2.setInt(1, firstId);
                    try (ResultSet rs2 = ps2.executeQuery()) {
                        if (rs2.next()) {
                            startSession(request, response, rs2);
                        } else {
                            showMessage(response,
                                    "Incorrect name, roll number or password.",
                                    false);
                        }
                    }
                }
            }
        }
    }

    private void handlePasswordChange(HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        HttpSession session = request.getSession(false);

        if (session == null
                || session.getAttribute("studentId") == null) {
            showMessage(response, "Please log in again.", false);
            return;
        }

        String currentPassword = request.getParameter("currentPassword");
        String newPassword = request.getParameter("newPassword");
        String confirmPassword = request.getParameter("confirmPassword");

        if (currentPassword == null || newPassword == null
                || confirmPassword == null
                || currentPassword.isEmpty()
                || newPassword.isEmpty()
                || confirmPassword.isEmpty()) {
            showMessage(response,
                    "Please complete all password fields.", false);
            return;
        }

        if (newPassword.length() < 6) {
            showMessage(response,
                    "The new password must contain at least 6 characters.",
                    false);
            return;
        }

        if (!newPassword.equals(confirmPassword)) {
            showMessage(response,
                    "The new password and confirmation do not match.",
                    false);
            return;
        }

        int studentId = (Integer) session.getAttribute("studentId");

        String sql = "UPDATE students SET password = ? "
                + "WHERE id = ? AND password = ?";

        try (Connection con = getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, newPassword);
            ps.setInt(2, studentId);
            ps.setString(3, currentPassword);

            if (ps.executeUpdate() == 0) {
                showMessage(response,
                        "Your current password is incorrect.", false);
                return;
            }
        } catch (SQLException e) {
            log("Password update error", e);
            showMessage(response,
                    "Unable to update your password.", false);
            return;
        }

        response.sendRedirect(request.getContextPath() + "/Login");
    }

    private void renderStudentDocument(PrintWriter out, ResultSet rs,
            String documentType, String contextPath) throws SQLException {
        String fullName = getValue(rs, "full_name");
        String rollNumber = getValue(rs, "roll_number");
        String department = getValue(rs, "department");
        String semester = getValue(rs, "semester");
        String email = getValue(rs, "email");
        String marks = getValue(rs, "marks");
        String grade = getValue(rs, "grade");

        if (fullName.isEmpty()) {
            fullName = getValue(rs, "username");
        }
        if (department.isEmpty()) {
            department = "CSM-E";
        }
        if (semester.isEmpty()) {
            semester = "3";
        }
        if (marks.isEmpty()) {
            marks = "Not entered";
        }
        if (grade.isEmpty()) {
            grade = "Not assigned";
        }

        String title = "marks".equals(documentType)
                ? "ACADEMIC MARKS MEMO" : "BONAFIDE CERTIFICATE";
        responseDocumentHeaders(out, title);
        out.println("<div class='toolbar'><a href='" + contextPath
                + "/Login#documents'>&larr; Back to Documents</a>"
                + "<button onclick='window.print()'>Print / Save as PDF</button></div>");
        out.println("<main class='paper'><div class='watermark'>UNOFFICIAL DEMO COPY</div>");
        out.println("<div class='demo-banner'>UNOFFICIAL SAMPLE — NOT VALID AS AN OFFICIAL COLLEGE DOCUMENT</div>");
        out.println("<header class='doc-header'><div class='seal'>ACET</div><div><h1>ADITYA COLLEGE OF ENGINEERING &amp; TECHNOLOGY</h1><p>Student Academic Portal • Sample Document Preview</p></div></header>");
        out.println("<div class='rule'></div><h2>" + title + "</h2>");

        if ("marks".equals(documentType)) {
            out.println("<p class='intro'>Student academic details as currently stored in the demonstration portal.</p>");
            out.println("<table class='record-table'><tr><th>Student Name</th><td>" + escapeHtml(fullName) + "</td></tr>");
            out.println("<tr><th>Roll Number</th><td>" + escapeHtml(rollNumber) + "</td></tr>");
            out.println("<tr><th>Department</th><td>" + escapeHtml(department) + "</td></tr>");
            out.println("<tr><th>Semester</th><td>" + escapeHtml(semester) + "</td></tr>");
            out.println("<tr><th>Portal Marks / GPA Value</th><td>" + escapeHtml(marks) + "</td></tr>");
            out.println("<tr><th>Portal Grade</th><td>" + escapeHtml(grade) + "</td></tr>");
            out.println("<tr><th>Student Email</th><td>" + escapeHtml(email) + "</td></tr></table>");
            out.println("<p class='fine-print'>This preview reflects portal database values only. It is not a verified transcript, examination result, or official marks memo.</p>");
        } else {
            out.println("<p class='intro certificate-copy'>This is a sample bonafide certificate layout for the student portal project. The details below are drawn from the current portal record and have not been verified or approved by the college.</p>");
            out.println("<p class='certificate-body'>This is to present the portal record of <strong>" + escapeHtml(fullName)
                    + "</strong> (Roll Number: <strong>" + escapeHtml(rollNumber)
                    + "</strong>), showing the department as <strong>" + escapeHtml(department)
                    + "</strong> and semester as <strong>" + escapeHtml(semester)
                    + "</strong>. This sample is generated solely to demonstrate the document feature of the student portal.</p>");
            out.println("<p class='fine-print'>A bonafide certificate becomes valid only when issued and authenticated by an authorised college official. This sample has no official status.</p>");
        }
        out.println("<div class='signature-row'><div><div class='signature-line'></div><strong>Student Record</strong><br><span>Portal-generated sample</span></div><div><div class='signature-line'></div><strong>Authorised College Office</strong><br><span>Approval not provided</span></div></div>");
        out.println("<footer>Aditya College of Engineering &amp; Technology • Student Portal Demonstration</footer>");
        out.println("</main></body></html>");
    }

    private void responseDocumentHeaders(PrintWriter out, String title) {
        out.println("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        out.println("<title>" + escapeHtml(title) + " | Sample Preview</title><style>");
        out.println("*{box-sizing:border-box}body{margin:0;background:#dbe2ec;color:#1d2a3d;font-family:Arial,Helvetica,sans-serif;padding:24px}.toolbar{max-width:900px;margin:0 auto 16px;display:flex;justify-content:space-between;gap:12px}.toolbar a,.toolbar button{border:0;border-radius:7px;padding:11px 15px;background:#243d63;color:#fff;text-decoration:none;font-weight:bold;cursor:pointer;font-size:14px}.paper{position:relative;overflow:hidden;max-width:900px;min-height:900px;margin:auto;padding:45px 55px;background:#fff;border:1px solid #b9c5d5;box-shadow:0 12px 35px #263a5722}.watermark{position:absolute;top:44%;left:4%;width:92%;text-align:center;font-size:52px;font-weight:900;color:rgba(156,35,35,.09);transform:rotate(-25deg);pointer-events:none;white-space:nowrap}.demo-banner{position:relative;text-align:center;border:2px solid #9f2e35;color:#8c2029;padding:10px;font-size:13px;font-weight:900;letter-spacing:.6px;margin-bottom:24px}.doc-header{position:relative;display:flex;align-items:center;justify-content:center;gap:18px;text-align:center}.seal{width:75px;height:75px;border:3px double #25436d;border-radius:50%;display:flex;align-items:center;justify-content:center;color:#25436d;font-weight:900;font-size:20px;flex-shrink:0}.doc-header h1{font-size:23px;color:#1c365d;margin:0 0 8px;line-height:1.35}.doc-header p{margin:0;color:#5a6880;font-size:12px}.rule{height:4px;background:#263f68;margin:25px 0}.paper h2{text-align:center;color:#1f3557;font-size:22px;letter-spacing:1px;margin:28px 0}.intro{position:relative;text-align:center;line-height:1.7;color:#45556c}.record-table{position:relative;width:100%;border-collapse:collapse;margin:28px 0}.record-table th,.record-table td{border:1px solid #8998ad;padding:13px 15px;text-align:left;font-size:15px}.record-table th{width:38%;background:#d5deeb;color:#172f51}.record-table td{background:#f5f7fa}.certificate-copy{margin:35px 0}.certificate-body{position:relative;font-size:17px;line-height:2.2;text-align:justify;margin:35px 0}.fine-print{position:relative;border-left:4px solid #9f2e35;background:#f9eeee;padding:12px 14px;color:#77252b;font-size:12px;line-height:1.7;margin-top:28px}.signature-row{position:relative;display:flex;justify-content:space-between;gap:40px;margin-top:85px;text-align:center}.signature-row>div{width:45%;font-size:13px}.signature-line{border-top:1px solid #58667a;margin-bottom:10px}.signature-row span{display:inline-block;margin-top:5px;font-size:11px;color:#6a778a}footer{position:relative;border-top:1px solid #ccd5e1;margin-top:55px;padding-top:12px;text-align:center;font-size:11px;color:#66748a}@media(max-width:650px){body{padding:8px}.paper{padding:22px 15px;min-height:auto}.doc-header{flex-direction:column}.doc-header h1{font-size:18px}.watermark{font-size:30px}.signature-row{gap:16px}.record-table th,.record-table td{padding:9px 7px;font-size:12px}}@media print{@page{size:A4;margin:12mm}body{padding:0;background:#fff}.toolbar{display:none}.paper{max-width:none;min-height:0;border:0;box-shadow:none;padding:10mm}.watermark{-webkit-print-color-adjust:exact;print-color-adjust:exact}.demo-banner,.fine-print,.record-table th{print-color-adjust:exact;-webkit-print-color-adjust:exact}}</style></head><body>");
    }

    private void renderDashboard(PrintWriter out,
            ResultSet rs, String notice, String contextPath)
            throws SQLException {

        String fullName = getValue(rs, "full_name");
        String username = getValue(rs, "username");
        String rollNumber = getValue(rs, "roll_number");
        String department = getValue(rs, "department");
        String semester = getValue(rs, "semester");
        String email = getValue(rs, "email");
        String gender = getValue(rs, "gender");
        String attendance = getValue(rs, "attendance");
        String marks = getValue(rs, "marks");
        String grade = getValue(rs, "grade");
        String achievement = getValue(rs, "achievement");

        if (fullName.isEmpty()) {
            fullName = username;
        }
        if (department.isEmpty()) {
            department = "CSE (AI&ML)";
        }
        if (semester.isEmpty()) {
            semester = "II-I";
        }

        String profilePhotoFile = photoFor(rollNumber, gender);

        out.println("<!DOCTYPE html>");
        out.println("<html lang='en'><head>");
        out.println("<meta charset='UTF-8'>");
        out.println("<meta name='viewport' "
                + "content='width=device-width, initial-scale=1.0'>");
        out.println("<title>Aditya College of Engineering &amp; "
                + "Technology | Student Portal</title>");

        out.println("<style>");
        out.println("*{box-sizing:border-box;}");
        out.println("html{scroll-behavior:smooth;}");
        out.println("body{margin:0;font-family:Arial,Helvetica,sans-serif;"
                + "background:#e9eef8;color:#172033;}");

        /* Sidebar */
        out.println(".sidebar{position:fixed;left:0;top:0;bottom:0;"
                + "width:255px;background:linear-gradient(180deg,#4c7db5 0%,#3b6598 52%,#315783 100%);color:#ffffff;"
                + "padding:22px 15px;overflow-x:hidden;overflow-y:auto;z-index:20;"
                + "transition:width .38s cubic-bezier(.22,.75,.25,1),padding .38s cubic-bezier(.22,.75,.25,1);"
                + "box-shadow:5px 0 22px rgba(35,70,120,.12);}");
        out.println(".sidebar-toggle{display:flex;align-items:center;justify-content:center;"
                + "width:38px;height:38px;margin:0 0 12px auto;border:1px solid #ffffff70;"
                + "border-radius:12px;background:linear-gradient(145deg,#86b9f4,#5e98dd);color:#fff;font-size:20px;"
                + "cursor:pointer;box-shadow:0 4px 10px #1e477433;transition:background .22s,transform .22s;}");
        out.println(".sidebar-toggle:hover{background:linear-gradient(145deg,#a5d0ff,#75aef0);transform:scale(1.03);}");
        out.println("body.sidebar-collapsed .sidebar{width:82px;padding:18px 10px;}");
        out.println("body.sidebar-collapsed .main-content{margin-left:82px;}");
        out.println("body.sidebar-collapsed .brand{font-size:0;text-align:center;padding:8px 0 18px;}");
        out.println("body.sidebar-collapsed .brand:before{content:'A';font-size:25px;color:#69a9ff;}");
        out.println("body.sidebar-collapsed .brand span,body.sidebar-collapsed .college-short,"
                + "body.sidebar-collapsed .nav-title,body.sidebar-collapsed .nav-link span:not(.nav-icon),"
                + "body.sidebar-collapsed .logout-label{display:none;}");
        out.println("body.sidebar-collapsed .nav-link{display:flex;justify-content:center;"
                + "padding:12px 4px;font-size:21px;}");
        out.println("body.sidebar-collapsed .nav-icon{margin:0;font-size:22px;}");
        out.println(".brand{font-size:25px;font-weight:bold;padding:8px 10px 5px;}");
        out.println(".brand span{color:#69a9ff;}");
        out.println(".college-short{font-size:11px;color:#b8c7e9;"
                + "padding:0 10px 24px;line-height:1.7;}");
        out.println(".nav-title{font-size:11px;color:#91a4ce;"
                + "letter-spacing:1.2px;padding:10px;margin-bottom:6px;}");
        out.println(".nav-link{display:flex;align-items:center;gap:11px;color:#f8fbff;text-decoration:none;"
                + "padding:10px 11px;margin-bottom:5px;border-radius:12px;min-height:47px;"
                + "font-size:14px;font-weight:600;transition:background .22s ease,color .22s ease,box-shadow .22s ease;}");
        out.println(".nav-link:hover{background:rgba(255,255,255,.19);color:#fff;box-shadow:0 4px 12px rgba(13,35,65,.10);}");
        out.println(".nav-link.active{background:linear-gradient(110deg,#ffffff,#e7f2ff);color:#2456a0;"
                + "box-shadow:0 5px 15px rgba(13,35,65,.12);}");

        /* Main area */
        out.println(".main-content{margin-left:255px;height:100vh;"
                + "overflow-y:auto;padding:20px 23px 24px;"
                + "background:#e9eef8;transition:margin-left .25s ease;}");
        out.println(".main-panel{min-height:calc(100vh - 44px);"
                + "padding:25px;background:#f8faff;"
                + "border:1px solid #dce5f3;border-radius:25px;"
                + "box-shadow:0 8px 30px rgba(26,48,90,.08);"
                + "overflow:hidden;}");

        /* College header, logo, accreditation image, and portal details */
        out.println(".college-header{background:#fff;"
                + "border:1px solid #e0e7f2;border-radius:18px;"
                + "padding:19px 21px;display:grid;"
                + "grid-template-columns:62px minmax(0,1fr) max-content auto;"
                + "align-items:center;gap:18px;margin-bottom:20px;"
                + "box-shadow:0 4px 15px rgba(35,60,105,.045);}");

        out.println(".cmr-mark{width:62px;height:62px;flex-shrink:0;"
                + "border-radius:15px;overflow:hidden;background:#fff;"
                + "display:flex;align-items:center;justify-content:center;}");
        out.println(".college-logo{width:100%;height:100%;"
                + "object-fit:contain;border-radius:15px;display:block;}");
        out.println(".college-info{min-width:0;}");

        /* College banner image */
        out.println(".college-banner{display:block;width:100%;"
                + "height:125px;object-fit:contain;object-position:center;"
                + "background:#f0f5ff;border-radius:12px;"
                + "margin-bottom:20px;padding:5px;"
                + "border:1px solid #d7e3f5;"
                + "box-shadow:0 4px 12px rgba(35,60,105,.06);}");

        out.println(".college-name{font-size:20px;font-weight:bold;"
                + "color:#10244a;margin-bottom:7px;line-height:1.35;white-space:nowrap;}");
        out.println(".college-address{font-size:12px;color:#64748b;line-height:1.7;}");
        out.println(".accreditation-center{display:flex;align-items:center;"
                + "justify-content:center;width:max-content;min-width:0;"
                + "margin-left:20px;margin-right:10px;}");
        out.println(".accreditation-logo{display:block;width:180px;height:120px;"
                + "max-width:none;object-fit:contain;margin:0 auto;"
                + "filter:drop-shadow(0 3px 5px rgba(20,43,85,.12));"
                + "animation:accreditationFloat 3.5s ease-in-out infinite;}");
        out.println(".college-right{text-align:right;font-size:12px;"
                + "color:#34476a;line-height:1.8;min-width:145px;white-space:nowrap;}");
        out.println("@keyframes accreditationFloat{0%,100%{transform:translateY(0)}"
                + "50%{transform:translateY(-3px)}}");
        out.println(".naac{display:inline-block;border:1px solid #c8d9f8;"
                + "background:#edf4ff;padding:6px 10px;border-radius:8px;"
                + "font-weight:bold;margin-bottom:5px;}");

        out.println(".topbar{display:flex;justify-content:space-between;"
                + "align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:23px;}");
        out.println(".topbar h2{font-size:23px;margin:0;color:#17264a;}");
        out.println(".user-badge{background:white;border-radius:11px;"
                + "padding:11px 15px;font-size:13px;"
                + "border:1px solid #e0e7f2;}");

        /* Dashboard */
        out.println(".welcome-thought-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);"
                + "gap:18px;margin:0 0 20px;align-items:stretch;}");
        out.println(".welcome{background:linear-gradient(125deg,#3478d6 0%,#5da4f6 58%,#8cc7ff 100%);"
                + "color:white;padding:23px;border-radius:19px;margin:0;min-width:0;min-height:100%;"
                + "display:flex;flex-direction:column;justify-content:center;"
                + "box-shadow:0 8px 22px rgba(36,86,199,.15);}");
        out.println(".welcome h1{font-size:24px;margin:0 0 9px;line-height:1.3;overflow-wrap:anywhere;}");
        out.println(".welcome p{font-size:13px;margin:0;line-height:1.65;}");
        out.println(".thought-card{display:flex;gap:15px;align-items:center;min-width:0;"
                + "padding:19px 20px;margin:0;border-radius:18px;"
                + "background:linear-gradient(125deg,#fff1bd,#fff9e5 58%,#fffdf7);"
                + "border:1px solid #f0d477;box-shadow:0 7px 18px rgba(126,90,20,.08);}");
        out.println(".upcoming-stats-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.08fr);"
                + "gap:18px;margin:0 0 24px;align-items:stretch;}");
        out.println(".upcoming-stats-grid>.upcoming-card{width:100%;height:100%;min-width:0;margin:0;display:flex;align-items:stretch;}");
        out.println(".upcoming-stats-grid .upcoming-inner{width:100%;padding:20px;}");
        out.println(".upcoming-stats-grid .stats{grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin:0;}");
        out.println(".upcoming-stats-grid .stat-card{padding:16px 17px;min-width:0;display:flex;flex-direction:column;justify-content:center;}");
        out.println(".upcoming-stats-grid .stat-label{font-size:12px;margin-bottom:8px;}");
        out.println(".upcoming-stats-grid .stat-value{font-size:23px;}");
        out.println(".thought-icon{width:52px;height:52px;flex-shrink:0;display:flex;"
                + "align-items:center;justify-content:center;border-radius:16px;"
                + "font-size:27px;background:linear-gradient(145deg,#ffe8a3,#ffc94d);"
                + "box-shadow:inset 0 2px 2px #ffffffb8,0 5px 10px #b47b2033;}");
        out.println(".thought-label{font-size:11px;font-weight:800;letter-spacing:1.3px;"
                + "color:#946200;text-transform:uppercase;margin-bottom:7px;}");
        out.println(".thought-text{font-size:17px;line-height:1.65;font-weight:700;color:#493817;}");
        out.println(".thought-author{font-size:12px;color:#8b7449;margin-top:7px;}");
        out.println(".quick-title{font-size:20px;font-weight:800;color:#17264a;margin:0 0 14px;}");
        out.println(".quick-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin-bottom:24px;}");
        out.println(".quick-card{width:100%;min-height:158px;text-align:left;display:flex;"
                + "flex-direction:column;align-items:flex-start;gap:10px;padding:18px;"
                + "border:1px solid #f0d98a;border-radius:19px;cursor:pointer;"
                + "background:linear-gradient(145deg,#fffbe8 0%,#fff4c7 48%,#ffffff 100%);color:#283653;"
                + "box-shadow:0 6px 14px rgba(174,133,30,.10);"
                + "transform:translateY(0) scale(1);transform-origin:center;"
                + "transition:transform .28s cubic-bezier(.2,.8,.2,1),box-shadow .28s,border-color .25s,background .25s;}");
        out.println(".quick-card:hover,.quick-card:focus-visible{transform:translateY(-7px) scale(1.035);"
                + "border-color:#e9b934;background:linear-gradient(145deg,#fff0a8 0%,#fff8d8 52%,#fffef6 100%);"
                + "box-shadow:0 15px 27px rgba(193,145,23,.20);outline:none;z-index:1;}");
        out.println(".quick-card:active{transform:translateY(-1px) scale(.97);box-shadow:0 5px 10px rgba(193,145,23,.14);}");
        out.println(".quick-icon{width:52px;height:52px;display:flex;align-items:center;"
                + "justify-content:center;border-radius:16px;font-size:28px;"
                + "background:linear-gradient(145deg,#fff0a8,#ffd45e);"
                + "box-shadow:inset 0 2px 2px #ffffffcc,0 5px 10px #bd8c2730;"
                + "transition:transform .28s cubic-bezier(.2,.8,.2,1);}");
        out.println(".quick-card:hover .quick-icon{transform:translateY(-2px) rotate(-4deg) scale(1.08);}");
        out.println(".quick-card:nth-child(6n+2) .quick-icon{background:linear-gradient(145deg,#c9f7df,#69ddb0);}");
        out.println(".quick-card:nth-child(6n+3) .quick-icon{background:linear-gradient(145deg,#ffd9b0,#ffad70);}");
        out.println(".quick-card:nth-child(6n+4) .quick-icon{background:linear-gradient(145deg,#f6d7ff,#d9a7ff);}");
        out.println(".quick-card:nth-child(6n+5) .quick-icon{background:linear-gradient(145deg,#c9edff,#79c9f5);}");
        out.println(".quick-card:nth-child(6n) .quick-icon{background:linear-gradient(145deg,#c8f7f1,#65d9ce);}");
        out.println(".quick-name{font-size:16px;font-weight:850;line-height:1.4;}");
        out.println(".quick-description{font-size:13px;color:#687389;line-height:1.5;}");
        out.println(".nav-icon{width:33px;height:33px;flex-shrink:0;display:inline-flex;"
                + "align-items:center;justify-content:center;border-radius:11px;font-size:18px;"
                + "background:linear-gradient(145deg,#c5e4ff,#82b9f5);color:#153d75;"
                + "box-shadow:inset 0 1px 1px #ffffffcc,0 3px 7px #153d7518;transition:transform .22s,filter .22s;}");
        out.println(".nav-link:hover .nav-icon{transform:scale(1.06);filter:saturate(1.18);}");
        out.println(".nav-link:nth-of-type(2) .nav-icon{background:linear-gradient(145deg,#ffe2a9,#ffc65b);}");
        out.println(".nav-link:nth-of-type(3) .nav-icon{background:linear-gradient(145deg,#c8f5df,#72d7a9);}");
        out.println(".nav-link:nth-of-type(4) .nav-icon{background:linear-gradient(145deg,#f1d8ff,#c7a0f5);}");
        out.println(".nav-link:nth-of-type(5) .nav-icon{background:linear-gradient(145deg,#ffe0d1,#ffad8a);}");
        out.println(".nav-link:nth-of-type(6) .nav-icon{background:linear-gradient(145deg,#c8f0ff,#7fcff4);}");
        out.println(".nav-link:nth-of-type(7) .nav-icon{background:linear-gradient(145deg,#ffdfec,#ff9ec5);}");
        out.println(".nav-link:nth-of-type(8) .nav-icon{background:linear-gradient(145deg,#d8f4b8,#a5d86c);}");
        out.println(".nav-link:nth-of-type(9) .nav-icon{background:linear-gradient(145deg,#ffe7b5,#ffc66c);}");
        out.println(".nav-link:nth-of-type(10) .nav-icon{background:linear-gradient(145deg,#d4e4ff,#9bbcff);}");
        out.println(".nav-link:nth-of-type(11) .nav-icon{background:linear-gradient(145deg,#c6f4ec,#76d8c7);}");
        out.println(".nav-link:nth-of-type(12) .nav-icon{background:linear-gradient(145deg,#ffd6d6,#ff9f9f);}");
        out.println(".nav-link:nth-of-type(13) .nav-icon{background:linear-gradient(145deg,#d9f0ff,#8fd0ff);}");
        out.println(".nav-link:nth-of-type(14) .nav-icon{background:linear-gradient(145deg,#fde2ff,#e3a8f5);}");
        out.println(".nav-link.active .nav-icon{background:linear-gradient(145deg,#ffe7a1,#ffc34f);color:#654300;}");
        out.println(".academic-tag{display:inline-block;margin-top:15px;"
                + "padding:7px 11px;border-radius:9px;background:#ffffff25;"
                + "font-size:12px;}");
        out.println(".stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));"
                + "gap:15px;margin:0 0 25px;}");
        out.println(".stats .stat-card{animation:statRise .65s cubic-bezier(.2,.75,.25,1) both;"
                + "border-top:3px solid #5b8def;background:linear-gradient(145deg,#fff,#f6f9ff);}");
        out.println(".stats .stat-card:nth-child(2){animation-delay:.08s;border-top-color:#22b58b;}");
        out.println(".stats .stat-card:nth-child(3){animation-delay:.16s;border-top-color:#f4a340;}");
        out.println(".stats .stat-card:nth-child(4){animation-delay:.24s;border-top-color:#a78bfa;}");
        out.println("@keyframes statRise{from{opacity:0;transform:translateY(14px) scale(.985)}"
                + "to{opacity:1;transform:translateY(0) scale(1)}}");
        out.println("@media(prefers-reduced-motion:reduce){.quick-card,.quick-icon,.stats .stat-card,.accreditation-logo{"
                + "animation:none!important;transition:none!important;}}");
        out.println(".stat-card,.section{background:white;border-radius:17px;"
                + "padding:21px;border:1px solid #e1e8f3;"
                + "box-shadow:0 4px 15px rgba(35,60,105,.045);}");
        out.println(".stat-card{transition:transform .22s ease,box-shadow .22s ease;}");
        out.println(".stat-card:hover{transform:translateY(-3px);"
                + "box-shadow:0 8px 20px rgba(35,60,105,.09);}");
        out.println(".stat-label{font-size:13px;color:#65728a;margin-bottom:12px;}");
        out.println(".stat-value{font-size:25px;font-weight:bold;color:#2456c7;"
                + "overflow-wrap:anywhere;}");
        out.println(".upcoming-card{display:block;width:100%;text-align:left;"
                + "border:1px solid #b8c9e6;border-radius:18px;padding:0;"
                + "margin:0 0 24px;background:linear-gradient(115deg,#142b55,#2858a7);"
                + "color:#fff;box-shadow:0 9px 22px rgba(20,43,85,.17);"
                + "overflow:hidden;cursor:pointer;transition:transform .2s,box-shadow .2s;}");
        out.println(".upcoming-card:hover{transform:translateY(-2px);"
                + "box-shadow:0 13px 28px rgba(20,43,85,.23);background:linear-gradient(115deg,#102448,#2452a0);}");
        out.println(".upcoming-inner{display:flex;align-items:center;gap:18px;"
                + "padding:21px 23px;}");
        out.println(".upcoming-clock{width:58px;height:58px;flex-shrink:0;"
                + "display:flex;align-items:center;justify-content:center;"
                + "background:#ffffff1c;border:1px solid #ffffff3b;"
                + "border-radius:15px;font-size:27px;}");
        out.println(".upcoming-copy{min-width:0;flex:1;}");
        out.println(".upcoming-label{font-size:11px;text-transform:uppercase;"
                + "letter-spacing:1.3px;color:#c8dcff;font-weight:bold;margin-bottom:7px;}");
        out.println(".upcoming-subject{font-size:22px;line-height:1.3;"
                + "font-weight:800;margin-bottom:6px;overflow-wrap:anywhere;}");
        out.println(".upcoming-meta{font-size:13px;color:#e2ebff;line-height:1.7;}");
        out.println(".upcoming-live{display:inline-block;margin-top:9px;"
                + "background:#dbeafe;color:#173a75;border-radius:999px;"
                + "padding:5px 9px;font-size:11px;font-weight:bold;}");
        out.println(".upcoming-arrow{font-size:25px;color:#dbeafe;padding:0 4px;}");

        /* Latest Announcements card */
        out.println(".announcements-card{background:#fff;border:1px solid #dce5f3;"
                + "border-radius:18px;padding:21px;margin:0 0 25px;"
                + "box-shadow:0 5px 17px rgba(35,60,105,.06);}");
        out.println(".announcements-heading{display:flex;align-items:center;"
                + "justify-content:space-between;gap:12px;flex-wrap:wrap;"
                + "margin-bottom:17px;}");
        out.println(".announcements-heading h3{font-size:20px;color:#17264a;"
                + "margin:0;display:flex;align-items:center;gap:10px;}");
        out.println(".announcement-heading-icon{width:42px;height:42px;"
                + "display:flex;align-items:center;justify-content:center;"
                + "border-radius:13px;background:#e1edff;font-size:23px;}");
        out.println(".announcement-count{background:#e8f1ff;color:#2456a0;"
                + "padding:7px 11px;border-radius:999px;font-size:11px;"
                + "font-weight:bold;white-space:nowrap;}");
        out.println(".announcement-list{display:grid;gap:12px;}");
        out.println(".announcement-item{display:flex;align-items:flex-start;"
                + "gap:13px;padding:15px;border:1px solid #e5ebf5;"
                + "border-radius:13px;background:#f9fbff;"
                + "transition:transform .2s,box-shadow .2s;}");
        out.println(".announcement-item:hover{transform:translateY(-2px);"
                + "box-shadow:0 6px 15px rgba(35,60,105,.07);}");
        out.println(".announcement-dot{width:10px;height:10px;"
                + "margin-top:6px;border-radius:50%;background:#4285ed;"
                + "flex-shrink:0;}");
        out.println(".announcement-content{flex:1;min-width:0;}");
        out.println(".announcement-title{font-size:14px;font-weight:800;"
                + "color:#1d3154;margin-bottom:6px;line-height:1.5;}");
        out.println(".announcement-description{font-size:13px;color:#64748b;"
                + "line-height:1.7;overflow-wrap:anywhere;}");
        out.println(".announcement-meta{display:flex;align-items:center;"
                + "gap:9px;flex-wrap:wrap;margin-top:9px;}");
        out.println(".announcement-date{font-size:11px;color:#77849a;}");
        out.println(".announcement-tag{display:inline-block;padding:5px 8px;"
                + "border-radius:6px;background:#e2edff;color:#2456a0;"
                + "font-size:10px;font-weight:800;text-transform:uppercase;}");
        out.println(".announcement-tag.important{background:#ffeded;color:#b42332;}");
        out.println(".announcement-tag.academic{background:#e2f8ec;color:#17764b;}");
        out.println(".announcement-tag.event{background:#fff0d9;color:#925700;}");
        out.println(".announcement-view-all{border:0;background:#edf4ff;color:#2456a0;"
                + "padding:10px 13px;border-radius:9px;font-size:12px;"
                + "font-weight:bold;cursor:pointer;white-space:nowrap;}");
        out.println(".announcement-view-all:hover{background:#dceaff;}");
        out.println("@media(max-width:600px){.announcements-card{padding:15px;}"
                + ".announcements-heading h3{font-size:18px;}"
                + ".announcement-item{padding:12px;gap:10px;}}");


        /* Page switching */
        out.println(".section{display:none;margin-bottom:22px;}");
        out.println(".section.active{display:block;animation:pageAppear .38s ease both;}");
        out.println("@keyframes pageAppear{from{opacity:0;transform:translateY(13px);}"
                + "to{opacity:1;transform:translateY(0);}}");
        out.println(".section-heading{font-size:20px;font-weight:bold;"
                + "margin-bottom:17px;color:#17264a;}");
        out.println(".section-subtitle{font-size:13px;color:#64748b;"
                + "margin-top:-8px;margin-bottom:16px;line-height:1.6;}");
        out.println(".details{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));"
                + "gap:13px;}");
        out.println(".profile-hero{display:flex;align-items:center;gap:28px;"
                + "padding:25px;margin-bottom:20px;border:1px solid #cdddf5;"
                + "border-radius:20px;background:linear-gradient(135deg,#f8fbff,#e7efff);"
                + "box-shadow:0 8px 22px rgba(35,60,105,.07);}");
        out.println(".profile-photo{width:220px;height:260px;object-fit:cover;"
                + "object-position:center 24%;border-radius:18px;border:4px solid #fff;"
                + "box-shadow:0 9px 24px #172b4d2b;flex-shrink:0;}");
        out.println(".profile-photo.avatar{object-fit:contain;object-position:center;"
                + "background:linear-gradient(145deg,#eaf2ff,#d6e6ff);}");
        out.println(".profile-hero-name{font-size:27px;font-weight:800;color:#17345f;margin-bottom:12px;}");
        out.println(".profile-hero-sub{font-size:15px;color:#526581;line-height:1.9;}");
        out.println(".student-name-cell{display:flex;align-items:center;gap:10px;}");
        out.println(".classmate-avatar{width:43px;height:43px;object-fit:cover;"
                + "object-position:center top;border-radius:50%;border:2px solid #fff;"
                + "box-shadow:0 2px 7px #172b4d22;flex-shrink:0;}");
        out.println(".student-name-text{white-space:normal;min-width:130px;}");
        out.println(".student-name-button{border:0;background:transparent;padding:0;"
                + "color:#2456a0;font:inherit;font-weight:750;text-align:left;"
                + "cursor:pointer;text-decoration:underline;text-decoration-color:#b9ccef;"
                + "text-underline-offset:3px;}");
        out.println(".student-name-button:hover{color:#12356e;text-decoration-color:#2456a0;"
                + "background:transparent;transform:none;}");
        out.println(".student-modal-overlay{display:none;position:fixed;inset:0;z-index:9999;"
                + "background:rgba(8,20,45,.68);padding:20px;align-items:center;"
                + "justify-content:center;backdrop-filter:blur(5px);}");
        out.println(".student-modal-overlay.open{display:flex;}");
        out.println(".student-modal{position:relative;width:min(680px,100%);max-height:90vh;"
                + "overflow-y:auto;background:#fff;border-radius:22px;padding:26px;"
                + "box-shadow:0 25px 80px #07152b66;animation:studentModalIn .2s ease;}");
        out.println("@keyframes studentModalIn{from{opacity:0;transform:translateY(12px) scale(.98);}"
                + "to{opacity:1;transform:translateY(0) scale(1);}}");
        out.println(".student-modal-close{position:absolute;right:15px;top:12px;width:38px;height:38px;"
                + "display:flex;align-items:center;justify-content:center;padding:0;margin:0;"
                + "border:0;border-radius:50%;background:#edf2fb;color:#20375d;font-size:25px;"
                + "font-family:Arial,sans-serif;font-weight:400;cursor:pointer;line-height:1;"
                + "text-align:center;box-sizing:border-box;}");
        out.println(".student-modal-close:hover{background:#dbe5f7;color:#20375d;transform:none;}");
        out.println(".student-modal-top{display:flex;align-items:center;gap:20px;padding:8px 35px 22px 0;}");
        out.println(".student-modal-photo{width:116px;height:132px;object-fit:cover;object-position:center top;"
                + "border-radius:17px;border:3px solid #e5edfb;background:#edf3ff;flex-shrink:0;}");
        out.println(".student-modal-name{font-size:22px;font-weight:850;color:#172d54;margin-bottom:8px;overflow-wrap:anywhere;}");
        out.println(".student-modal-roll{font-size:13px;color:#64748b;overflow-wrap:anywhere;}");
        out.println(".student-modal-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;}");
        out.println(".student-modal-item{padding:14px;border:1px solid #e4ebf6;border-radius:13px;background:#f7f9fd;min-width:0;}");
        out.println(".student-modal-label{font-size:11px;color:#6b7890;text-transform:uppercase;letter-spacing:.6px;margin-bottom:6px;}");
        out.println(".student-modal-value{font-size:15px;font-weight:750;color:#1d3154;overflow-wrap:anywhere;}");
        out.println("@media(max-width:520px){.student-modal{padding:20px;}.student-modal-top{gap:13px;}"
                + ".student-modal-photo{width:84px;height:100px;}.student-modal-name{font-size:18px;}"
                + ".student-modal-grid{grid-template-columns:1fr;}.student-modal-overlay{padding:10px;}}");
        out.println(".detail{background:#f5f8ff;border:1px solid #e7edf8;"
                + "border-radius:12px;padding:15px;overflow-wrap:anywhere;}");
        out.println(".detail-label{color:#68768c;font-size:12px;margin-bottom:7px;}");
        out.println(".detail-value{font-size:14px;font-weight:bold;color:#1e2e4a;}");
        out.println(".notice{padding:12px 14px;border-radius:11px;margin-bottom:17px;"
                + "background:#e0f2fe;color:#075985;font-size:14px;}");
        out.println(".placeholder{color:#65728a;line-height:1.8;font-size:14px;}");

        /* Tables */
        out.println(".table-wrap{width:100%;overflow-x:auto;"
                + "border-radius:11px;}");
        out.println(".timetable{width:100%;border-collapse:separate;"
                + "border-spacing:0;min-width:1050px;font-size:12px;}");
        out.println(".timetable th{background:#10244a;color:white;padding:12px 9px;"
                + "text-align:center;border-right:1px solid #34476a;"
                + "border-bottom:1px solid #dbe3f0;}");
        out.println(".timetable th:first-child{border-top-left-radius:9px;}");
        out.println(".timetable th:last-child{border-top-right-radius:9px;}");
        out.println(".timetable td{padding:11px 8px;text-align:center;"
                + "border-right:1px solid #dbe3f0;"
                + "border-bottom:1px solid #dbe3f0;line-height:1.5;background:#fff;}");
        out.println(".timetable td:first-child{font-weight:bold;"
                + "background:#edf3ff;color:#10244a;"
                + "border-left:1px solid #dbe3f0;}");
        out.println(".timetable tbody tr:first-child td{border-top:1px solid #dbe3f0;}");
        out.println(".break-cell{background:#fff3d6!important;"
                + "font-weight:bold;color:#8a5a00;}");
        out.println(".lab-cell{background:#e8f2ff!important;"
                + "color:#194da8;font-weight:bold;}");
        out.println(".timetable-note{font-size:12px;color:#64748b;"
                + "margin-top:12px;line-height:1.8;}");
        out.println(".course-table{width:100%;border-collapse:separate;"
                + "border-spacing:0;border:1px solid #aebbd0;border-radius:10px;"
                + "overflow:hidden;font-size:13px;color:#1e293b;}");
        out.println(".course-table thead th{background:#263d63;color:#fff;"
                + "text-align:left;padding:14px 13px;font-size:12px;"
                + "letter-spacing:.35px;text-transform:uppercase;"
                + "border-right:1px solid #40577d;border-bottom:2px solid #172b4d;}");
        out.println(".course-table thead th:last-child{border-right:0;}");
        out.println(".course-table tbody td{padding:13px;border-right:1px solid #d0d8e5;"
                + "border-bottom:1px solid #cbd4e2;vertical-align:middle;line-height:1.55;}");
        out.println(".course-table tbody tr:nth-child(odd) td{background:#e3e9f2;}");
        out.println(".course-table tbody tr:nth-child(even) td{background:#f1f4f8;}");
        out.println(".course-table tbody td:first-child{font-weight:700;color:#243b60;"
                + "white-space:nowrap;}");
        out.println(".course-table tbody td:nth-child(2){font-weight:600;color:#26364e;}");
        out.println(".course-table tbody td:last-child{border-right:0;overflow-wrap:anywhere;}");
        out.println(".course-table tbody tr:last-child td{border-bottom:0;}");
        out.println(".course-table tbody tr:hover td{background:#d1dbea;transition:background .18s ease;}");
        out.println(".course-table a{color:#173f7a;font-weight:600;text-decoration:none;"
                + "border-bottom:1px dotted #526f9c;}");
        out.println(".course-table a:hover{color:#0b2346;border-bottom-style:solid;}");
        /* Larger, easier-to-read Faculty & Mentors table */
        out.println(".faculty-table{font-size:16px;min-width:850px;}");
        out.println(".faculty-table thead th{font-size:15px;padding:17px 16px;letter-spacing:.45px;}");
        out.println(".faculty-table tbody td{font-size:16px;padding:17px 16px;line-height:1.65;}");
        out.println(".faculty-table tbody td:first-child{font-size:15px;}");
        out.println(".faculty-table a{font-size:15px;font-weight:700;}");
        out.println(".faculty-table-wrap{margin-top:16px;}");
        out.println("@media(max-width:700px){.faculty-table thead th{font-size:14px;}");
        out.println(".faculty-table tbody td{font-size:15px;padding:14px 12px;}}");

        /* Documents section styling: keep CSS inside the page style block. */
        out.println(".document-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:20px;margin-top:20px;}");
        out.println(".document-card{background:#e0e8f5;border:1px solid #b8c7dc;border-radius:16px;padding:22px;box-shadow:0 5px 15px rgba(20,40,75,.09);}");
        out.println(".document-card h3{color:#172f59;font-size:20px;margin:13px 0 9px;}");
        out.println(".document-card p{font-size:14px;line-height:1.7;color:#42536e;min-height:70px;}");
        out.println(".document-icon{width:52px;height:52px;border-radius:12px;background:#233f70;color:#fff;display:flex;align-items:center;justify-content:center;font-weight:800;font-size:17px;letter-spacing:1px;}");
        out.println(".certificate-icon{background:#46566f;}");
        out.println(".document-button{display:inline-block;background:#233f70;color:#fff;text-decoration:none;font-size:14px;font-weight:bold;padding:12px 15px;border-radius:9px;margin-top:8px;}");
        out.println(".document-button:hover{background:#152b50;}");
        out.println(".document-notice{margin-top:20px;padding:13px 15px;background:#d5deeb;border-left:4px solid #526783;color:#293b55;border-radius:7px;line-height:1.6;font-size:13px;}");

        /* Clickable dashboard cards */
        out.println(".stat-link{text-decoration:none;color:inherit;cursor:pointer;}");
        out.println(".stat-link:hover{border-color:#9dbdf0;}");
        out.println(".stat-link:focus-visible{outline:3px solid #8fb6f2;outline-offset:2px;}");
        out.println(".stat-more{margin-top:9px;font-size:11px;font-weight:800;color:#2456a0;letter-spacing:.3px;}");
        out.println(".stat-link:hover .stat-more{text-decoration:underline;}");
        out.println(".att-info{background:#e8f1ff;color:#1f4a8f;border:1px solid #c8d9f8;}");

        out.println("/* Holiday calendar */");
        out.println(".cal-hero{position:relative;overflow:hidden;display:flex;align-items:center;gap:16px;padding:20px 22px;margin-bottom:18px;border-radius:18px;color:#fff;background:linear-gradient(120deg,#2456c7,#4389f4);box-shadow:0 8px 20px rgba(36,86,199,.16);}");
        out.println(".cal-hero-holiday{background:linear-gradient(120deg,#ff7a3d,#ffb347 55%,#ffd86b);box-shadow:0 8px 22px rgba(255,140,60,.28);}");
        out.println(".cal-hero-sunday{background:linear-gradient(120deg,#e0506a,#f68b9c);}");
        out.println(".cal-hero-emoji{position:relative;width:72px;height:72px;flex-shrink:0;display:flex;align-items:center;justify-content:center;border-radius:20px;background:rgba(255,255,255,.25);font-size:42px;animation:bounceEmoji 1.8s ease-in-out infinite;}");
        out.println("@keyframes bounceEmoji{0%,100%{transform:translateY(0) rotate(-4deg);}50%{transform:translateY(-6px) rotate(4deg);}}");
        out.println(".cal-hero-label{font-size:11px;font-weight:800;letter-spacing:1.4px;opacity:.9;}");
        out.println(".cal-hero-title{font-size:26px;font-weight:900;line-height:1.25;margin:3px 0 5px;}");
        out.println(".cal-hero-text{font-size:13px;line-height:1.7;}");
        out.println(".confetti{position:absolute;left:0;right:0;top:0;bottom:0;pointer-events:none;}");
        out.println(".confetti span{position:absolute;top:-26px;font-size:18px;animation:confettiFall 4.2s linear infinite;}");
        out.println("@keyframes confettiFall{to{transform:translateY(190px) rotate(360deg);opacity:0;}}");
        out.println(".cal-layout{display:grid;grid-template-columns:minmax(0,1.7fr) minmax(0,1fr);gap:18px;align-items:start;}");
        out.println(".cal-card{padding:18px;background:#fff;border:1px solid #dce5f3;border-radius:18px;box-shadow:0 5px 17px rgba(35,60,105,.06);}");
        out.println(".cal-head{display:flex;align-items:center;gap:10px;margin-bottom:14px;}");
        out.println(".cal-head-mid{flex:1;text-align:center;}");
        out.println(".cal-title{font-size:19px;font-weight:800;color:#17264a;}");
        out.println(".cal-count{font-size:12px;color:#c0620c;font-weight:700;margin-top:2px;}");
        out.println(".cal-nav{width:38px;height:38px;padding:0;border-radius:10px;background:#edf4ff;color:#2456a0;font-size:22px;font-weight:800;line-height:1;}");
        out.println(".cal-nav:hover{background:#dceaff;}");
        out.println(".cal-today-btn{padding:9px 13px;border-radius:9px;background:#17264a;font-size:12px;}");
        out.println(".cal-week,.cal-grid{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:6px;}");
        out.println(".cal-week{margin-bottom:6px;}");
        out.println(".cal-week span{text-align:center;font-size:11px;font-weight:800;letter-spacing:.6px;text-transform:uppercase;color:#68768c;}");
        out.println(".cal-week span:first-child{color:#b42332;}");
        out.println(".cal-cell{position:relative;min-height:78px;padding:7px;display:flex;flex-direction:column;gap:2px;overflow:hidden;border-radius:12px;background:#f5f8ff;border:1px solid #e3eaf6;cursor:pointer;transition:transform .18s,box-shadow .18s;}");
        out.println(".cal-cell:hover{transform:translateY(-3px);box-shadow:0 8px 16px rgba(35,60,105,.12);}");
        out.println(".cal-empty{background:transparent;border:0;cursor:default;min-height:0;}");
        out.println(".cal-empty:hover{transform:none;box-shadow:none;}");
        out.println(".cal-num{width:24px;height:24px;display:flex;align-items:center;justify-content:center;font-size:13px;font-weight:800;color:#1e2e4a;border-radius:50%;}");
        out.println(".cal-sunday{background:#fdeeee;border-color:#f3cfcf;}");
        out.println(".cal-sunday .cal-num{color:#b42332;}");
        out.println(".cal-holiday{background:linear-gradient(145deg,#fff3c4,#ffd9a0);border-color:#f0c36a;}");
        out.println(".cal-emoji{font-size:20px;line-height:1.1;}");
        out.println(".cal-name{font-size:10px;font-weight:700;line-height:1.25;color:#7a3e00;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}");
        out.println(".cal-today{box-shadow:inset 0 0 0 2px #2456c7;}");
        out.println(".cal-today .cal-num{background:#2456c7;color:#fff;}");
        out.println(".cal-sel{outline:3px solid #8fb6f2;outline-offset:1px;}");
        out.println(".cal-legend{display:flex;gap:16px;flex-wrap:wrap;margin-top:14px;font-size:12px;color:#64748b;}");
        out.println(".lg{display:inline-block;width:12px;height:12px;margin-right:6px;border-radius:4px;vertical-align:-1px;}");
        out.println(".lg-hol{background:#ffc766;}.lg-sun{background:#f5bcbc;}.lg-today{background:#2456c7;}");
        out.println(".cal-detail{display:flex;align-items:center;gap:14px;padding:16px;border-radius:16px;background:#f5f8ff;border:1px solid #e3eaf6;}");
        out.println(".cal-d-holiday{background:linear-gradient(145deg,#fff3c4,#ffe0b0);border-color:#f0c36a;}");
        out.println(".cal-d-sunday{background:#fdeeee;border-color:#f3cfcf;}");
        out.println(".cal-d-emoji{width:54px;height:54px;flex-shrink:0;display:flex;align-items:center;justify-content:center;border-radius:16px;background:rgba(255,255,255,.7);font-size:30px;}");
        out.println(".cal-d-title{font-size:17px;font-weight:800;color:#17264a;}");
        out.println(".cal-d-text{margin-top:3px;font-size:12px;color:#5b6b83;line-height:1.7;}");
        out.println(".cal-side-title{margin:18px 0 10px;font-size:15px;color:#17264a;}");
        out.println(".cal-up{display:flex;align-items:center;gap:12px;padding:10px 12px;margin-bottom:8px;border-radius:12px;background:#fff;border:1px solid #e3eaf6;}");
        out.println(".cal-up-emoji{font-size:24px;}");
        out.println(".cal-up-main{flex:1;min-width:0;}");
        out.println(".cal-up-main b{display:block;font-size:13px;color:#1d3154;}");
        out.println(".cal-up-main small{font-size:11px;color:#77849a;}");
        out.println(".cal-up-when{font-size:11px;font-weight:800;color:#c0620c;white-space:nowrap;}");
        out.println("@media(max-width:900px){.cal-layout{grid-template-columns:1fr;}}");
        out.println("@media(max-width:700px){.cal-cell{min-height:52px;padding:4px;}.cal-name{display:none;}.cal-emoji{font-size:16px;}.cal-hero{padding:14px;}.cal-hero-title{font-size:20px;}.cal-hero-emoji{width:56px;height:56px;font-size:32px;}.cal-card{padding:12px;}}");
        out.println("/* Help and support */");
        out.println(".support-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:14px;margin-bottom:18px;}");
        out.println(".support-card{padding:18px;background:#f5f8ff;border:1px solid #e7edf8;border-radius:16px;}");
        out.println(".support-icon{font-size:28px;margin-bottom:6px;}");
        out.println(".support-card h3{margin:0 0 8px;font-size:16px;color:#17264a;}");
        out.println(".support-card p{margin:0 0 6px;font-size:13px;line-height:1.7;color:#42536e;overflow-wrap:anywhere;}");
        out.println(".support-card a{color:#173f7a;font-weight:700;}");
        out.println(".support-small{font-size:12px!important;color:#77849a!important;}");
        out.println(".faq{margin-top:6px;}");
        out.println(".faq h3{font-size:16px;color:#17264a;margin:0 0 10px;}");
        out.println(".faq details{padding:12px 14px;margin-bottom:8px;background:#fff;border:1px solid #e3eaf6;border-radius:12px;}");
        out.println(".faq summary{cursor:pointer;font-weight:700;font-size:14px;color:#1d3154;}");
        out.println(".faq details p{margin:8px 0 0;font-size:13px;line-height:1.7;color:#64748b;}");

        out.println("/* Fees and payments */");
        out.println(".fee-hero{display:flex;align-items:center;gap:18px;padding:22px 24px;margin-bottom:18px;border-radius:20px;color:#fff;background:linear-gradient(120deg,#16a34a,#34d399 65%,#a3e635);box-shadow:0 9px 22px rgba(22,163,74,.22);}");
        out.println(".fee-hero-mid{background:linear-gradient(120deg,#f97316,#fb923c 55%,#fbbf24);box-shadow:0 9px 22px rgba(249,115,22,.25);}");
        out.println(".fee-hero-gov{background:linear-gradient(120deg,#2563eb,#0ea5e9 60%,#2dd4bf);box-shadow:0 9px 22px rgba(37,99,235,.25);}");
        out.println(".fee-hero-emoji{width:78px;height:78px;flex-shrink:0;display:flex;align-items:center;justify-content:center;border-radius:22px;background:rgba(255,255,255,.25);font-size:44px;animation:bounceEmoji 1.9s ease-in-out infinite;}");
        out.println(".fee-hero-label{font-size:11px;font-weight:800;letter-spacing:1.4px;opacity:.92;}");
        out.println(".fee-hero-title{margin:3px 0 6px;font-size:25px;font-weight:900;line-height:1.25;}");
        out.println(".fee-hero-text{font-size:13px;line-height:1.75;max-width:640px;}");
        out.println(".fee-stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-bottom:18px;}");
        out.println(".fee-stat{padding:16px 18px;background:#fff;border:1px solid #e1e8f3;border-top:4px solid #3b82f6;border-radius:16px;box-shadow:0 4px 14px rgba(35,60,105,.05);}");
        out.println(".fee-stat small{display:block;font-size:12px;color:#65728a;margin-bottom:6px;}");
        out.println(".fee-stat b{display:block;font-size:22px;color:#17264a;}");
        out.println(".fee-stat span{display:block;margin-top:5px;font-size:11px;color:#77849a;}");
        out.println(".fee-s-green{border-top-color:#22c55e;}.fee-s-orange{border-top-color:#f59e0b;}.fee-s-violet{border-top-color:#8b5cf6;}");
        out.println(".fee-progress{padding:18px 20px;margin-bottom:6px;background:#fff;border:1px solid #e1e8f3;border-radius:16px;}");
        out.println(".fee-progress-head{display:flex;justify-content:space-between;font-size:14px;color:#17264a;margin-bottom:10px;}");
        out.println(".fee-progress-head span{font-weight:800;color:#16a34a;}");
        out.println(".fee-bar{height:16px;border-radius:999px;background:#e8eef9;overflow:hidden;}");
        out.println(".fee-bar-fill{height:100%;border-radius:999px;background:linear-gradient(90deg,#22c55e,#86efac);}");
        out.println(".fee-fill-gov{background:linear-gradient(90deg,#2563eb,#2dd4bf);}");
        out.println(".section.active .fee-bar-fill{animation:feeGrow 1.2s cubic-bezier(.2,.8,.2,1) both;}");
        out.println("@keyframes feeGrow{from{width:0;}}");
        out.println(".fee-progress-foot{display:flex;justify-content:space-between;margin-top:7px;font-size:11px;color:#77849a;}");
        out.println(".fee-title{margin:22px 0 10px;font-size:16px;font-weight:800;color:#17264a;}");
        out.println(".chip{display:inline-block;padding:4px 10px;border-radius:999px;font-size:11px;font-weight:800;white-space:nowrap;}");
        out.println(".chip-ok{background:#dcfce7;color:#15803d;}.chip-due{background:#ffedd5;color:#c2410c;}.chip-gov{background:#dbeafe;color:#1d4ed8;}");
        out.println(".fee-plan{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px;}");
        out.println(".fee-inst{padding:16px;border-radius:16px;border:1px solid;}");
        out.println(".fee-inst-ok{background:#f0fdf4;border-color:#bbf7d0;}");
        out.println(".fee-inst-due{background:#fff7ed;border-color:#fed7aa;}");
        out.println(".fee-inst-no{font-size:12px;font-weight:800;letter-spacing:.5px;text-transform:uppercase;color:#65728a;}");
        out.println(".fee-inst-amt{margin:6px 0;font-size:22px;font-weight:900;color:#17264a;}");
        out.println(".fee-inst-st{font-size:12px;font-weight:700;color:#475a78;}");
        out.println(".fee-gov{margin-top:20px;padding:22px;border-radius:20px;color:#0c2a5c;background:linear-gradient(135deg,#e0f2fe,#ccfbf1 70%,#fef9c3);border:1px solid #93c5fd;}");
        out.println(".fee-gov-badge{display:inline-block;padding:6px 14px;border-radius:999px;background:#2563eb;color:#fff;font-size:12px;font-weight:800;letter-spacing:.6px;}");
        out.println(".fee-gov h3{margin:12px 0 8px;font-size:20px;}");
        out.println(".fee-gov p{margin:0 0 10px;font-size:13px;line-height:1.8;}");
        out.println(".fee-gov ul{margin:0;padding:0;list-style:none;display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:8px;}");
        out.println(".fee-gov li{padding:10px 12px;border-radius:12px;background:rgba(255,255,255,.7);font-size:13px;font-weight:700;}");
        out.println(".fee-info-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:14px;}");
        out.println(".fee-info{padding:18px;border-radius:16px;border:1px solid #e7edf8;background:#f5f8ff;}");
        out.println(".fee-info h3{margin:0 0 8px;font-size:16px;color:#17264a;}");
        out.println(".fee-info p,.fee-info li{font-size:13px;line-height:1.8;color:#42536e;}");
        out.println(".fee-info ul,.fee-info ol{margin:6px 0 0;padding-left:20px;}");
        out.println(".fee-info a{color:#173f7a;font-weight:700;}");
        out.println(".fee-note{margin-top:10px!important;font-size:11px!important;color:#77849a!important;font-style:italic;}");
        out.println(".fee-i-rules{background:#f5f3ff;border-color:#ddd6fe;}.fee-i-pay{background:#ecfdf5;border-color:#a7f3d0;}");
        out.println(".fee-i-gov{background:#eff6ff;border-color:#bfdbfe;}.fee-i-tip{background:#fefce8;border-color:#fde68a;}.fee-i-help{background:#fdf2f8;border-color:#fbcfe8;}");
        out.println(".fee-demo{margin-top:18px;padding:12px 14px;border-radius:12px;background:#eef2f7;border-left:4px solid #94a3b8;font-size:12px;color:#475569;line-height:1.7;}");
        out.println("@media(max-width:900px){.fee-stats{grid-template-columns:repeat(2,minmax(0,1fr));}}");
        out.println("@media(max-width:600px){.fee-hero{padding:16px;gap:12px;}.fee-hero-emoji{width:56px;height:56px;font-size:32px;}.fee-hero-title{font-size:19px;}.fee-stat b{font-size:18px;}}");

        /* Class toppers card */
        out.println(".toppers-card{margin:0 0 24px;padding:22px;background:linear-gradient(135deg,#fffdf3,#fff6d8 60%,#ffffff);border:1px solid #f0d98a;border-radius:20px;box-shadow:0 7px 18px rgba(174,133,30,.10);}");
        out.println(".toppers-head{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:18px;}");
        out.println(".toppers-title{margin:0;font-size:20px;font-weight:800;color:#17264a;}");
        out.println(".toppers-sub{margin:4px 0 0;font-size:12px;color:#7a6a3a;}");
        out.println(".rank-pill{padding:8px 14px;border-radius:999px;background:#17264a;color:#fff;font-size:12px;font-weight:800;white-space:nowrap;}");
        out.println(".podium{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;align-items:end;}");
        out.println(".topper{position:relative;padding:18px 12px 16px;text-align:center;background:#fff;border:1px solid #ecdca0;border-radius:18px;box-shadow:0 5px 14px rgba(120,90,20,.08);}");
        out.println(".topper.rank1{order:2;padding-top:26px;padding-bottom:22px;border:2px solid #f3b72c;background:linear-gradient(160deg,#fff8dc,#ffffff);}");
        out.println(".topper.rank2{order:1;}.topper.rank3{order:3;}");
        out.println(".topper-photo{position:relative;width:72px;height:72px;margin:0 auto 10px;}");
        out.println(".rank1 .topper-photo{width:88px;height:88px;}");
        out.println(".topper-photo img{display:block;width:100%;height:100%;object-fit:cover;object-position:center top;border-radius:50%;border:3px solid #fff;background:#eaf2ff;box-shadow:0 5px 12px rgba(23,43,77,.2);}");
        out.println(".medal{position:absolute;right:-8px;bottom:-4px;width:30px;height:30px;display:flex;align-items:center;justify-content:center;border-radius:50%;background:#fff;font-size:18px;box-shadow:0 3px 8px rgba(0,0,0,.2);}");
        out.println(".topper-name{font-size:14px;font-weight:800;color:#1d3154;line-height:1.35;overflow-wrap:anywhere;}");
        out.println(".topper-roll{margin-top:2px;font-size:11px;color:#77849a;}");
        out.println(".topper-score{margin-top:8px;font-size:24px;font-weight:900;color:#2456c7;}");
        out.println(".rank1 .topper-score{font-size:28px;color:#b36b00;}");
        out.println(".topper-score small{font-size:11px;font-weight:700;color:#77849a;}");
        out.println(".topper-grade{display:inline-block;margin-top:4px;padding:3px 10px;border-radius:999px;background:#e2f8ec;color:#17764b;font-size:11px;font-weight:800;}");
        out.println(".topper-you{display:inline-block;margin-left:6px;padding:2px 7px;border-radius:6px;background:#2456c7;color:#fff;font-size:10px;font-weight:800;vertical-align:middle;}");
        out.println(".toppers-note{margin:14px 0 0;font-size:11px;color:#8b7a4a;}");
        out.println("@media(max-width:700px){.toppers-card{padding:14px;}.podium{grid-template-columns:1fr;}"
                + ".topper.rank1,.topper.rank2,.topper.rank3{order:0;}.toppers-title{font-size:18px;}}");

        /* Attendance bar chart */
        out.println(".att-card{margin-top:20px;padding:22px;background:#fff;border:1px solid #dce5f3;border-radius:18px;box-shadow:0 5px 17px rgba(35,60,105,.06);}");
        out.println(".att-title{font-size:18px;font-weight:800;color:#17264a;margin:0 0 4px;}");
        out.println(".att-sub{font-size:13px;color:#64748b;margin:0 0 6px;line-height:1.6;}");
        out.println(".att-plot{position:relative;height:260px;margin:34px 0 0 42px;border-left:1px solid #b9c6d8;border-bottom:1px solid #b9c6d8;}");
        out.println(".att-grid{position:absolute;left:0;right:0;border-top:1px dashed #dfe7f3;}");
        out.println(".att-grid span,.att-zero{position:absolute;left:-42px;top:-8px;width:36px;text-align:right;font-size:11px;color:#77849a;}");
        out.println(".att-zero{top:auto;bottom:-8px;}");
        out.println(".att-line{position:absolute;left:0;right:0;z-index:2;border-top:2px dashed #e0861a;}");
        out.println(".att-line span{position:absolute;left:6px;top:-24px;padding:3px 9px;border-radius:7px;background:#fff3dc;color:#a35a00;font-size:11px;font-weight:800;}");
        out.println(".att-bars{position:absolute;left:0;right:0;top:0;bottom:0;display:flex;align-items:flex-end;justify-content:space-around;padding:0 4%;}");
        out.println(".att-col{width:22%;max-width:120px;height:100%;display:flex;align-items:flex-end;justify-content:center;}");
        out.println(".att-bar{position:relative;width:100%;border-radius:12px 12px 0 0;transform-origin:bottom;box-shadow:0 6px 14px rgba(35,60,105,.16);}");
        out.println(".att-bar b{position:absolute;left:-10px;right:-10px;top:-26px;text-align:center;font-size:14px;color:#1e2e4a;}");
        out.println(".section.active .att-bar{animation:barGrow .9s cubic-bezier(.2,.8,.2,1) both;}");
        out.println("@keyframes barGrow{from{transform:scaleY(0);}to{transform:scaleY(1);}}");
        out.println(".bar-me{background:linear-gradient(180deg,#5da4f6,#2456c7);}");
        out.println(".bar-avg{background:linear-gradient(180deg,#6fdcb8,#1fa57c);}");
        out.println(".bar-top{background:linear-gradient(180deg,#cbb8ff,#8a6df0);}");
        out.println(".att-labels{display:flex;justify-content:space-around;padding:10px 4% 0;margin-left:42px;}");
        out.println(".att-labels div{width:22%;max-width:120px;text-align:center;font-size:12px;font-weight:700;color:#475a78;}");
        out.println(".att-status{margin-top:18px;padding:13px 15px;border-radius:12px;font-size:14px;line-height:1.7;font-weight:600;}");
        out.println(".att-ok{background:#e2f8ec;color:#17764b;border:1px solid #bfe9d2;}");
        out.println(".att-low{background:#ffeded;color:#b42332;border:1px solid #f5c4c8;}");
        out.println("@media(max-width:700px){.att-card{padding:14px;}.att-plot{height:210px;margin-left:36px;}"
                + ".att-grid span,.att-zero{left:-36px;width:30px;}.att-labels{margin-left:36px;}"
                + ".att-labels div{font-size:11px;}.att-bar b{font-size:12px;}}");

        /* Forms */
        out.println("input{display:block;width:100%;max-width:420px;padding:12px;"
                + "margin:9px 0 15px;border:1px solid #cbd5e1;"
                + "border-radius:10px;font-size:14px;background:#fff;"
                + "transition:border-color .2s,box-shadow .2s;}");
        out.println("input:focus{outline:none;border-color:#4389f4;"
                + "box-shadow:0 0 0 3px rgba(67,137,244,.13);}");
        out.println("button{background:#2456c7;color:white;border:0;"
                + "padding:12px 19px;border-radius:10px;cursor:pointer;"
                + "font-size:14px;transition:background .2s,transform .2s;}");
        out.println("button:hover{background:#1745aa;transform:translateY(-1px);}");
        out.println(".footer{text-align:center;color:#778399;font-size:12px;"
                + "margin-top:25px;line-height:1.8;padding:12px;}");

        out.println("body:not(.dashboard-view) .dashboard-only{display:none!important;}");

        out.println("@media(max-width:1250px){"
                + ".main-panel{padding:20px;}"
                + ".college-name{font-size:18px;}"
                + ".quick-grid{grid-template-columns:repeat(3,minmax(0,1fr));}"
                + ".stats{grid-template-columns:repeat(2,minmax(0,1fr));}}");

        out.println("@media(max-width:850px){"
                + ".college-right{display:none;}"
                + ".college-header{grid-template-columns:46px minmax(0,1fr) max-content;"
                + "align-items:center;gap:12px;}"
                + ".accreditation-center{width:max-content;min-width:0;"
                + "margin-left:8px;margin-right:0;}"
                + ".accreditation-logo{width:145px;height:100px;}"
                + ".main-content{padding:14px;}"
                + ".main-panel{padding:17px;border-radius:20px;}"
                + ".welcome-thought-grid,.upcoming-stats-grid{grid-template-columns:1fr;}"
                + ".welcome{min-height:0;}"
                + ".upcoming-stats-grid>.upcoming-card{min-height:190px;}"
                + ".quick-grid{grid-template-columns:repeat(2,minmax(0,1fr));}}");

        out.println("@media(max-width:700px){"
                + ".sidebar{width:185px;padding:15px 9px;}"
                + ".main-content{margin-left:185px;padding:9px;}"
                + ".main-panel{min-height:calc(100vh - 18px);"
                + "padding:12px;border-radius:17px;}"
                + ".details{grid-template-columns:1fr;}"
                + ".welcome-thought-grid,.upcoming-stats-grid{grid-template-columns:1fr;gap:13px;}"
                + ".upcoming-stats-grid .stats{grid-template-columns:repeat(2,minmax(0,1fr));}"
                + "body.sidebar-collapsed .sidebar{width:72px;padding:15px 7px;}"
                + "body.sidebar-collapsed .main-content{margin-left:72px;}"
                + ".quick-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;}"
                + ".quick-card{min-height:130px;padding:13px;}"
                + ".college-name{font-size:15px;}"
                + ".college-header{padding:12px;gap:10px;border-radius:13px;"
                + "grid-template-columns:46px minmax(0,1fr) max-content;}"
                + ".accreditation-center{width:max-content;min-width:0;"
                + "margin-left:0;margin-right:0;}"
                + ".accreditation-logo{width:110px;height:82px;}"
                + ".cmr-mark{width:46px;height:46px;font-size:14px;"
                + "border-radius:11px;}"
                + ".college-logo{border-radius:11px;}"
                + ".college-banner{height:95px;border-radius:10px;}"
                + ".welcome{padding:20px;border-radius:14px;}"
                + ".welcome h1{font-size:21px;}"
                + ".quick-name{font-size:15px;}"
                + ".quick-description{font-size:12px;}"
                + ".quick-card{min-height:140px;}"
                + ".topbar h2{font-size:20px;}"
                + ".section,.stat-card{padding:15px;border-radius:13px;}"
                + ".stat-value{font-size:23px;}"
                + ".upcoming-inner{padding:17px;gap:12px;}"
                + ".upcoming-clock{width:45px;height:45px;font-size:22px;}"
                + ".upcoming-subject{font-size:18px;}"
                + ".upcoming-arrow{font-size:20px;}}");

        out.println("@media(prefers-reduced-motion:reduce){"
                + "*,*::before,*::after{scroll-behavior:auto!important;"
                + "animation-duration:.01ms!important;"
                + "transition-duration:.01ms!important;}}");

        out.println("@media(max-width:600px){.profile-hero{align-items:center;gap:16px;padding:14px;}"
                + ".profile-photo{width:150px;height:185px;}.profile-hero-name{font-size:20px;}"
                + ".profile-hero-sub{font-size:13px;}.thought-card{padding:15px;gap:12px;}"
                + ".thought-icon{width:43px;height:43px;font-size:22px;}.thought-text{font-size:15px;}"
                + ".quick-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:9px;}"
                + ".quick-card{min-height:120px;padding:11px;}.quick-icon{width:41px;height:41px;font-size:22px;}}");
        out.println("@media(max-width:420px){.profile-hero{flex-direction:column;align-items:flex-start;}"
                + ".profile-photo{width:100%;max-width:220px;height:245px;}.quick-grid{grid-template-columns:1fr 1fr;}"
                + ".upcoming-stats-grid .stats{grid-template-columns:1fr 1fr;}.quick-card{padding:12px;min-height:132px;}"
                + ".quick-name{font-size:14px;}.quick-description{font-size:11px;}}");
        out.println("</style></head><body class='dashboard-view sidebar-collapsed'>");

        /* Sidebar */
        out.println("<aside class='sidebar' id='portalSidebar'>");
        out.println("<button type='button' class='sidebar-toggle' id='sidebarToggle' "
                + "onclick='toggleSidebar()' aria-label='Collapse sidebar' "
                + "title='Collapse or expand sidebar'>&#10005;</button>");
        out.println("<div class='brand'>ACET <span>Portal</span></div>");
        out.println("<div class='college-short'>"
                + "ADITYA COLLEGE OF ENGINEERING &amp; TECHNOLOGY<br>"
                + "Department of CSE (AI&amp;ML)</div>");
        out.println("<div class='nav-title'>STUDENT MENU</div>");

        nav(out, "dashboard", "Dashboard");
        nav(out, "profile", "My Profile");
        nav(out, "students", "Student Details");
        nav(out, "academics", "Academic Details");
        nav(out, "timetable", "Class Timetable");
        nav(out, "calendar", "Holiday Calendar");
        nav(out, "attendance", "Attendance");
        nav(out, "results", "Marks & Results");
        nav(out, "announcements", "Announcements");
        nav(out, "fees", "Fees & Payments");
        nav(out, "documents", "Documents");
        nav(out, "mentors", "Faculty & Mentors");
        nav(out, "support", "Help & Support");
        nav(out, "password", "Change Password");

        out.println("<a class='nav-link' href='Login?action=logout'>"
                + "<span class='nav-icon' aria-hidden='true'>&#128682;</span>"
                + "<span class='logout-label'>Logout</span></a>");
        out.println("</aside>");

        out.println("<main class='main-content'>");
        out.println("<div class='main-panel'>");

        /* College header with separate accreditation image */
        out.println("<header class='college-header'>");
        out.println("<div class='cmr-mark'>"
                + "<img class='college-logo' src='"
                + contextPath + COLLEGE_LOGO + "' "
                + "alt='ACET College Logo'>"
                + "</div>");
        out.println("<div class='college-info'>"
                + "<div class='college-name'>"
                + "Aditya College of Engineering &amp; Technology</div>"
                + "<div class='college-address'>"
                + "Department of Computer Science and Engineering (AI&amp;ML)"
                + "<br>Student Academic Portal</div></div>");
        out.println("<div class='accreditation-center'>"
                + "<img class='accreditation-logo' src='" + contextPath
                + "/images/acet-accreditation.png' "
                + "alt='ACET accreditation emblem' "
                + "onerror=\"this.style.display='none'\">"
                + "</div>");
        out.println("<div class='college-right'>"
                + "<div class='naac'>STUDENT PORTAL</div>"
                + "<div>B.Tech · II-I Semester<br>"
                + "Academic Year 2026–27</div></div>");
        out.println("</header>");

        out.println("<div class='topbar dashboard-only'>");
        out.println("<h2>Student Dashboard</h2>");
        out.println("<div class='user-badge'>Roll No: <b>"
                + escapeHtml(rollNumber) + "</b></div></div>");

        if (notice != null && !notice.isEmpty()) {
            out.println("<div class='notice dashboard-only'>"
                    + escapeHtml(notice) + "</div>");
        }

        out.println("<div class='welcome-thought-grid dashboard-only'>");
        out.println("<section class='welcome'>");
        out.println("<h1>Welcome, " + escapeHtml(fullName) + "!</h1>");
        out.println("<p>Your personal academic portal for "
                + "Aditya College of Engineering &amp; Technology.</p>");
        out.println("<span class='academic-tag'>CSE (AI&amp;ML) | E-Section "
                + "| II-I Semester</span></section>");
        out.println("<section class='thought-card' aria-label='Thought of the day'>");
        out.println("<div class='thought-icon' aria-hidden='true'>&#128161;</div><div>");
        out.println("<div class='thought-label'>Thought of the Day</div>");
        out.println("<div class='thought-text' id='dailyThought'>Small progress every day leads to big results.</div>");
        out.println("<div class='thought-author' id='dailyThoughtAuthor'>A reminder for your learning journey</div>");
        out.println("</div></section></div>");

        out.println("<div class='upcoming-stats-grid dashboard-only'>");
        out.println("<button type='button' class='upcoming-card' "
                + "onclick=\"return showPage('timetable')\" "
                + "aria-label='View full class timetable'>");
        out.println("<div class='upcoming-inner'><div class='upcoming-clock'>&#128197;</div>");
        out.println("<div class='upcoming-copy'><div class='upcoming-label' id='upcomingLabel'>"
                + "UPCOMING CLASS</div><div class='upcoming-subject' id='upcomingSubject'>"
                + "Checking your timetable...</div><div class='upcoming-meta' id='upcomingTime'>"
                + "Checking current time...</div><div class='upcoming-live' id='upcomingStatus'>"
                + "LIVE TIMETABLE</div></div><div class='upcoming-arrow'>&#8594;</div></div></button>");
        out.println("<div class='stats stats-compact'>");
        stat(out, "My Attendance",
                attendance.isEmpty() ? "Not available" : attendance + "%",
                "attendance");
        stat(out, "My Marks / SGPA",
                marks.isEmpty() ? "Not available" : marks, "results");
        stat(out, "Current Semester", semester, "academics");
        stat(out, "Academic Grade",
                grade.isEmpty() ? "Not available" : grade, "results");
        out.println("</div></div>");

        renderToppers(out, rollNumber, marks);

        out.println("<div class='quick-title dashboard-only'>Quick Access</div>");
        out.println("<div class='quick-grid dashboard-only' aria-label='Quick access to portal sections'>");
        quickCard(out, "profile", "&#128100;", "My Profile", "View your personal details");
        quickCard(out, "students", "&#128101;", "Student Details", "Find classmates and records");
        quickCard(out, "academics", "&#127891;", "Academic Details", "Review your course details");
        quickCard(out, "timetable", "&#128197;", "Class Timetable", "Check classes and periods");
        quickCard(out, "calendar", "&#127881;", "Holiday Calendar", "See holidays and working days");
        quickCard(out, "attendance", "&#9989;", "Attendance", "Review attendance percentage");
        quickCard(out, "results", "&#127919;", "Marks & Results", "View marks and grade details");
        quickCard(out, "announcements", "&#128227;", "Announcements", "Read portal updates");
        quickCard(out, "fees", "&#128176;", "Fees & Payments", "Review fee information");
        quickCard(out, "documents", "&#128196;", "Documents", "Open available documents");
        quickCard(out, "mentors", "&#129309;", "Faculty & Mentors", "Find faculty information");
        quickCard(out, "support", "&#127911;", "Help & Support", "Contact the portal team");
        quickCard(out, "password", "&#128274;", "Change Password", "Keep your account secure");

        out.println("</div>");


        /* My Profile */
        out.println("<section class='section' id='profile'>");
        heading(out, "My Profile");
        out.println("<div class='profile-hero'><img class='profile-photo"
                + "' src='"
                + contextPath + "/images/" + profilePhotoFile + "' alt='Profile picture'>"
                + "<div><div class='profile-hero-name'>" + escapeHtml(fullName)
                + "</div><div class='profile-hero-sub'>" + escapeHtml(rollNumber)
                + "<br>" + escapeHtml(department)
                + "<br>Gender: " + escapeHtml(gender.isEmpty() ? "Not specified" : gender)
                + "<br>Semester " + escapeHtml(semester)
                + "<br><span style='color:#14865b;font-weight:bold'>&#9679; Active Student</span>"
                + "</div></div></div>");
        out.println("<div class='details'>");
        detail(out, "Full Name", fullName);
        detail(out, "Roll Number", rollNumber);
        detail(out, "Username", username);
        detail(out, "Email", email);
        detail(out, "Gender", gender.isEmpty() ? "Not specified" : gender);
        out.println("</div></section>");

        /* Student Details */
        out.println("<section class='section' id='students'>");
        heading(out, "Student Details");
        out.println("<div class='section-subtitle'>"
                + "Classmates registered in the Student Portal.</div>");
        out.println("<input type='text' id='studentSearch' "
                + "placeholder='Search by name or roll number...'"
                + " oninput='searchStudents()'>");
        out.println("<div class='table-wrap'><table class='course-table' "
                + "id='studentsTable'><thead><tr>"
                + "<th>Student</th><th>Roll Number</th><th>Department</th>"
                + "<th>Semester</th><th>Gender</th><th>Attendance</th><th>Marks</th>"
                + "</tr></thead><tbody>");

        String studentSql
                = "SELECT full_name, roll_number, department, semester, "
                + "attendance, marks, gender FROM students ORDER BY id ASC";

        try (Connection studentCon = getConnection(); PreparedStatement studentPs
                = studentCon.prepareStatement(studentSql); ResultSet studentRs = studentPs.executeQuery()) {

            while (studentRs.next()) {
                String classmateName = getValue(studentRs, "full_name");
                String classmateRoll = getValue(studentRs, "roll_number").trim();
                String classmateGender = getValue(studentRs, "gender")
                        .trim().toLowerCase(java.util.Locale.ROOT);

                String genderAvatar = photoFor(classmateRoll, classmateGender);

                out.println("<tr>");
                out.print("<td><div class='student-name-cell'>");

                if (!genderAvatar.isEmpty()) {
                    String avatarPath = contextPath + "/images/" + genderAvatar;

                    out.print("<img class='classmate-avatar' src='" + avatarPath
                            + "' onerror=\"this.style.display='none'\" alt='3D "
                            + escapeHtml(classmateGender) + " avatar for "
                            + escapeHtml(classmateRoll) + "'>");
                }

                String classmateDepartment = getValue(studentRs, "department");
                String classmateSemester = getValue(studentRs, "semester");
                String classmateAttendance = getValue(studentRs, "attendance");
                String classmateMarks = getValue(studentRs, "marks");
                String avatarForModal = genderAvatar.isEmpty()
                        ? "" : contextPath + "/images/" + genderAvatar;

                out.println("<button type='button' class='student-name-button' "
                        + "onclick='openStudentModal(this)' "
                        + "data-name='" + escapeHtml(classmateName) + "' "
                        + "data-roll='" + escapeHtml(classmateRoll) + "' "
                        + "data-department='" + escapeHtml(classmateDepartment) + "' "
                        + "data-semester='" + escapeHtml(classmateSemester) + "' "
                        + "data-gender='" + escapeHtml(getValue(studentRs, "gender")) + "' "
                        + "data-attendance='" + escapeHtml(classmateAttendance) + "' "
                        + "data-marks='" + escapeHtml(classmateMarks) + "' "
                        + "data-photo='" + escapeHtml(avatarForModal) + "'>"
                        + escapeHtml(classmateName) + "</button>");
                out.println("</div></td>");
                out.println("<td>" + escapeHtml(
                        getValue(studentRs, "roll_number")) + "</td>");
                out.println("<td>" + escapeHtml(
                        getValue(studentRs, "department")) + "</td>");
                out.println("<td>" + escapeHtml(
                        getValue(studentRs, "semester")) + "</td>");
                String displayGender = getValue(studentRs, "gender");
                out.println("<td>" + escapeHtml(displayGender.isEmpty() ? "Not specified" : displayGender)
                        + "</td>");
                out.println("<td>" + escapeHtml(
                        getValue(studentRs, "attendance")) + "</td>");
                out.println("<td>" + escapeHtml(
                        getValue(studentRs, "marks")) + "</td>");
                out.println("</tr>");
            }
        } catch (SQLException e) {
            log("Unable to load student details", e);
            out.println("<tr><td colspan='7'>"
                    + "Unable to load student records. Ensure the students table has a gender column.</td></tr>");
        }

        out.println("</tbody></table></div></section>");

        /* Popup profile shown when a student name is clicked. */
        out.println("<div class='student-modal-overlay' id='studentModalOverlay' "
                + "role='dialog' aria-modal='true' aria-labelledby='studentModalName' "
                + "onclick='closeStudentModal(event)'>");
        out.println("<div class='student-modal' role='document'>");
        out.println("<button type='button' class='student-modal-close' "
                + "aria-label='Close student details' onclick='closeStudentModal()'>&times;</button>");
        out.println("<div class='student-modal-top'><img id='studentModalPhoto' "
                + "class='student-modal-photo' alt='Student portrait' "
                + "src='" + contextPath + "/images/classmate-boy.png' "
                + "onerror=\"this.style.display='none'\">");
        out.println("<div><div class='student-modal-name' id='studentModalName'>Student Name</div>"
                + "<div class='student-modal-roll' id='studentModalRoll'>Roll number</div></div></div>");
        out.println("<div class='student-modal-grid'>");
        out.println("<div class='student-modal-item'><div class='student-modal-label'>Department</div>"
                + "<div class='student-modal-value' id='studentModalDepartment'>—</div></div>");
        out.println("<div class='student-modal-item'><div class='student-modal-label'>Semester</div>"
                + "<div class='student-modal-value' id='studentModalSemester'>—</div></div>");
        out.println("<div class='student-modal-item'><div class='student-modal-label'>Gender</div>"
                + "<div class='student-modal-value' id='studentModalGender'>—</div></div>");
        out.println("<div class='student-modal-item'><div class='student-modal-label'>Attendance</div>"
                + "<div class='student-modal-value' id='studentModalAttendance'>—</div></div>");
        out.println("<div class='student-modal-item'><div class='student-modal-label'>Marks</div>"
                + "<div class='student-modal-value' id='studentModalMarks'>—</div></div>");
        out.println("</div></div></div>");

        /* Academic Details */
        out.println("<section class='section' id='academics'>");
        heading(out, "Academic Details");
        out.println("<div class='details'>");
        detail(out, "College",
                "Aditya College of Engineering & Technology");
        detail(out, "Department", department);
        detail(out, "Programme", "B.Tech");
        detail(out, "Semester", semester);
        detail(out, "Section", "E-Section");
        detail(out, "Academic Year", "2026–27");
        detail(out, "Grade", grade);
        detail(out, "Achievement", achievement);
        out.println("</div></section>");

        /* Timetable */
        out.println("<section class='section' id='timetable'>");
        heading(out, "II-I Semester — E-Section Timetable");
        out.println("<div class='section-subtitle'>Room No: 352 | "
                + "Effective from: 06 July 2026</div>");
        out.println("<div class='table-wrap'><table class='timetable'>");
        out.println("<thead><tr><th>Day</th><th>9:10–10:10</th>"
                + "<th>10:10–11:10</th><th>11:10–11:20</th>"
                + "<th>11:20–12:20</th><th>12:20–1:20</th>"
                + "<th>1:20–2:05</th><th>2:05–3:05</th>"
                + "<th>3:05–4:05</th></tr></thead><tbody>");

        out.println("<tr><td>MON</td>");
        timetableCell(out, "DAA");
        timetableCell(out, "SDE");
        out.println("<td class='break-cell'>BREAK</td>");
        timetableCell(out, "SDT");
        timetableCell(out, "OOPS");
        out.println("<td class='break-cell'>LUNCH</td>");
        timetableCell(out, "DBMS");
        timetableCell(out, "I&amp;E");
        out.println("</tr>");

        out.println("<tr><td>TUE</td>");
        out.println("<td colspan='2' class='lab-cell'>"
                + "DBMS LAB (B1) — Room 317<br>"
                + "OOPS LAB (B2) — Room 318</td>");
        out.println("<td class='break-cell'>BREAK</td>");
        timetableCell(out, "OOPS");
        timetableCell(out, "DAA");
        out.println("<td class='break-cell'>LUNCH</td>");
        timetableCell(out, "SDT");
        timetableCell(out, "SPORTS");
        out.println("</tr>");

        out.println("<tr><td>WED</td>");
        timetableCell(out, "OOPS");
        timetableCell(out, "DBMS");
        out.println("<td class='break-cell'>BREAK</td>");
        timetableCell(out, "DAA");
        timetableCell(out, "SDE");
        out.println("<td class='break-cell'>LUNCH</td>");
        out.println("<td colspan='2' class='lab-cell'>"
                + "OOPS LAB (B1) — Room 318<br>"
                + "DBMS LAB (B2) — Room 317</td></tr>");

        out.println("<tr><td>THU</td>");
        timetableCell(out, "SDE");
        timetableCell(out, "I&amp;E");
        out.println("<td class='break-cell'>BREAK</td>");
        timetableCell(out, "DBMS");
        timetableCell(out, "DAA");
        out.println("<td class='break-cell'>LUNCH</td>");
        out.println("<td colspan='2' class='lab-cell'>"
                + "CM LAB (B1) — Room 332<br>"
                + "DE&amp;V LAB (B2) — Room 320</td></tr>");

        out.println("<tr><td>FRI</td>");
        timetableCell(out, "CRT");
        timetableCell(out, "CRT");
        out.println("<td class='break-cell'>BREAK</td>");
        timetableCell(out, "CRT");
        timetableCell(out, "CRT");
        out.println("<td class='break-cell'>LUNCH</td>");
        timetableCell(out, "CRT");
        timetableCell(out, "CRT");
        out.println("</tr>");

        out.println("<tr><td>SAT</td>");
        timetableCell(out, "SDT");
        timetableCell(out, "SDE");
        out.println("<td class='break-cell'>BREAK</td>");
        out.println("<td colspan='2' class='lab-cell'>"
                + "DE&amp;V LAB (B1) — Room 320<br>"
                + "CM LAB (B2) — Room 332</td>");
        out.println("<td class='break-cell'>LUNCH</td>");
        timetableCell(out, "OOPS");
        timetableCell(out, "LIB");
        out.println("</tr>");

        out.println("</tbody></table></div>");
        out.println("<div class='timetable-note'><b>BREAK:</b> 11:10–11:20 AM "
                + "| <b>LUNCH:</b> 1:20–2:05 PM<br>"
                + "Lab blocks represent two consecutive periods. "
                + "B1 and B2 have different lab room allocations.</div>");
        out.println("</section>");

        /* Attendance */
        out.println("<section class='section' id='attendance'>");
        heading(out, "Attendance");
        out.println("<div class='details'>");
        detail(out, "My Attendance", attendance.isEmpty()
                ? "Not available" : attendance + "%");
        detail(out, "Roll Number", rollNumber);
        out.println("</div>");
        renderAttendanceChart(out, attendance);
        out.println("<p class='placeholder'>Attendance shown here is based "
                + "on the current student record in the database.</p>");
        out.println("</section>");

        /* Marks and Results */
        out.println("<section class='section' id='results'>");
        heading(out, "Marks & Results");
        out.println("<div class='details'>");
        detail(out, "Marks / SGPA", marks);
        detail(out, "Academic Grade", grade);
        out.println("</div>");
        renderMarksChart(out, marks, grade);
        out.println("<p class='placeholder'>Your marks and grade are loaded "
                + "from your student record.</p></section>");

        /* Announcements */
        out.println("<section class='section' id='announcements'>");
        heading(out, "Announcements");
        out.println("<p class='placeholder'>"
                + "No official announcements have been added yet."
                + "</p></section>");

        /* Fees */
        out.println("<section class='section' id='fees'>");
        heading(out, "Fees & Payments");
        renderFees(out, rollNumber);
        out.println("</section>");

        /* Documents */
        out.println("<section class='section' id='documents'>");
        heading(out, "Documents");
        out.println("<div class='section-subtitle'>"
                + "Generate a personalised sample using your student portal record. "
                + "These previews are for project demonstration only and are not official college-issued documents.</div>");
        out.println("<div class='document-grid'>");
        out.println("<article class='document-card'><div class='document-icon'>"
                + "<span>MM</span></div><h3>Academic Marks Memo</h3>"
                + "<p>View your name, roll number, department, semester, "
                + "portal GPA/marks, and grade in a printable sample layout.</p>"
                + "<a class='document-button' target='_blank' rel='noopener' href='"
                + contextPath + "/Login?action=document&amp;type=marks'>Preview Marks Memo</a></article>");
        out.println("<article class='document-card'><div class='document-icon certificate-icon'>"
                + "<span>BC</span></div><h3>Bonafide Certificate</h3>"
                + "<p>Preview a student bonafide certificate draft with your portal details "
                + "and a clear sample watermark.</p>"
                + "<a class='document-button' target='_blank' rel='noopener' href='"
                + contextPath + "/Login?action=document&amp;type=bonafide'>Preview Bonafide Certificate</a></article>");
        out.println("</div>");
        out.println("<p class='document-notice'><strong>Demo notice:</strong> "
                + "These are unofficial sample previews. For a valid marks memo or bonafide certificate, "
                + "request a verified document from the college office.</p></section>");


        /* Faculty and Mentors */
        out.println("<section class='section' id='mentors'>");
        heading(out, "Faculty & Mentors");
        out.println("<div class='section-subtitle'>"
                + "Contact the relevant faculty member by selecting their email address. "
                + "Please verify these suggested addresses with the college.</div>");
        out.println("<div class='table-wrap faculty-table-wrap'><table class='course-table faculty-table' id='facultyTable'>");
        out.println("<thead><tr><th>Course Code</th>"
                + "<th>Course</th><th>Faculty</th><th>Email</th></tr></thead><tbody>");

        course(out, "25MABS31", "Statistics and Decision Theory (SDT)",
                "Mr. J. Karunakar", "j.karunakar");
        course(out, "25CSPC31", "Design and Analysis of Algorithms (DAA)",
                "Mr. B. Anil Kumar", "b.anilkumar");
        course(out, "25CSPC32", "Database Management Systems (DBMS)",
                "Mrs. B. Annapoorna", "b.annapoorna");
        course(out, "25CSPC33", "OOP through Java (OOPS)",
                "Mr. P. Niranjan Reddy", "p.niranjanreddy");
        course(out, "25CSPC34", "Software Design and Engineering (SDE)",
                "Mr. V. Shiva Sai", "v.shivasai");
        course(out, "25MABS33", "Computational Mathematics Lab (CM LAB)",
                "Mr. J. Karunakar", "j.karunakar");
        course(out, "25CSPC35", "DBMS Lab",
                "Mrs. B. Annapoorna / Mrs. B. Shanthi",
                "b.annapoorna;b.shanthi");
        course(out, "25CSPC36", "OOP through Java Lab",
                "Mr. P. Niranjan Reddy / Mrs. D. Dhanalakshmi",
                "p.niranjanreddy;d.dhanalakshmi");
        course(out, "25CDSD31", "Data Exploration and Visualization Lab",
                "Mrs. K. Manimala / Mr. G. Vinayranjan",
                "k.manimala;g.vinayranjan");
        course(out, "25MSHS31", "Innovation and Entrepreneurship (I&E)",
                "Mrs. Lasya Bharani", "lasya.bharani");

        out.println("</tbody></table></div>");
        out.println("<p class='timetable-note'>"
                + "<b>Class Coordinator:</b> Mrs. B. Annapoorna — "
                + "<a href='mailto:b.annapoorna@acet.edu.in'>b.annapoorna@acet.edu.in</a><br>"
                + "<b>I Year Coordinator:</b> Dr. K. Sampath — "
                + "<a href='mailto:k.sampath@acet.edu.in'>k.sampath@acet.edu.in</a><br>"
                + "<b>Mentors:</b> K. Renuka — "
                + "<a href='mailto:k.renuka@acet.edu.in'>k.renuka@acet.edu.in</a>; "
                + "K. Rani Srujana — "
                + "<a href='mailto:k.ranisrujana@acet.edu.in'>k.ranisrujana@acet.edu.in</a>"
                + "</p></section>");


        /* Holiday Calendar */
        out.println("<section class='section' id='calendar'>");
        heading(out, "Holiday Calendar");
        out.println("<div class='section-subtitle'>Tap any date to see whether it is a holiday or a working day.</div>");
        out.println("<div id='calHero' class='cal-hero'></div>");
        out.println("<div class='cal-layout'><div class='cal-card'>");
        out.println("<div class='cal-head'>"
                + "<button type='button' class='cal-nav' onclick='calMove(-1)' aria-label='Previous month'>&#8249;</button>"
                + "<div class='cal-head-mid'><div class='cal-title' id='calTitle'></div><div class='cal-count' id='calCount'></div></div>"
                + "<button type='button' class='cal-nav' onclick='calMove(1)' aria-label='Next month'>&#8250;</button>"
                + "<button type='button' class='cal-today-btn' onclick='calGoToday()'>Today</button></div>");
        out.println("<div class='cal-week'><span>Sun</span><span>Mon</span><span>Tue</span><span>Wed</span>"
                + "<span>Thu</span><span>Fri</span><span>Sat</span></div>");
        out.println("<div class='cal-grid' id='calGrid'></div>");
        out.println("<div class='cal-legend'><span><i class='lg lg-hol'></i>Holiday</span>"
                + "<span><i class='lg lg-sun'></i>Sunday (weekly off)</span>"
                + "<span><i class='lg lg-today'></i>Today</span></div>");
        out.println("</div><div class='cal-side'><div id='calDetail' class='cal-detail'></div>"
                + "<h3 class='cal-side-title'>Upcoming holidays</h3><div id='calUpcoming'></div></div></div>");
        out.println("<p class='timetable-note'>Holiday dates follow the Telangana Government general holiday list for 2026. "
                + "Festivals that depend on moon sighting may change by a day, and the college's own academic calendar "
                + "can differ, so please confirm with the college office.</p>");
        out.println("</section>");

        /* Help and Support */
        out.println("<section class='section' id='support'>");
        heading(out, "Help & Support");
        out.println("<div class='section-subtitle'>Having a problem with the portal? Here is how to get help quickly.</div>");
        String mailLink = "mailto:" + SUPPORT_EMAIL + "?subject=Portal%20support%20-%20"
                + escapeHtml(rollNumber).replace(" ", "%20");
        out.println("<div class='support-grid'>");
        out.println("<div class='support-card'><div class='support-icon'>&#9993;</div><h3>Email Support</h3>"
                + "<p><a href='" + mailLink + "'>" + escapeHtml(SUPPORT_EMAIL) + "</a></p>"
                + "<p class='support-small'>Mention your roll number and describe the problem. A screenshot helps.</p></div>");
        out.println("<div class='support-card'><div class='support-icon'>&#128339;</div><h3>Support Hours</h3>"
                + "<p>" + escapeHtml(SUPPORT_HOURS) + "</p>"
                + "<p class='support-small'>Closed on Sundays and holidays. Check the Holiday Calendar.</p></div>");
        out.println("<div class='support-card'><div class='support-icon'>&#129309;</div><h3>Who to Contact</h3>"
                + "<p><b>Class Coordinator:</b> Mrs. B. Annapoorna<br><span class='support-small'>Attendance, marks and password reset</span></p>"
                + "<p><b>OOPs Faculty:</b> Mr. P. Niranjan Reddy<br><span class='support-small'>Portal project queries</span></p>"
                + "<p class='support-small'><a href='#mentors' onclick=\"return showPage('mentors')\">See all faculty emails</a></p></div>");
        out.println("<div class='support-card'><div class='support-icon'>&#128100;</div><h3>Your Details</h3>"
                + "<p><b>" + escapeHtml(fullName) + "</b><br>Roll No: " + escapeHtml(rollNumber) + "</p>"
                + "<p class='support-small'>Quote these when you contact support.</p></div>");
        out.println("</div>");
        out.println("<div class='faq'><h3>Frequently Asked Questions</h3>");
        out.println("<details><summary>I forgot my password</summary><p>The portal has no self-service reset yet. "
                + "Email support or ask your class coordinator with your roll number, and they can reset it. "
                + "After logging in, change it under Change Password.</p></details>");
        out.println("<details><summary>My attendance, marks or grade look wrong</summary><p>These values come from your record "
                + "in the portal database. Report the correct value with your roll number so it can be updated.</p></details>");
        out.println("<details><summary>My photo or personal details are wrong</summary><p>Your profile is loaded from the "
                + "student record. Contact support with the correction.</p></details>");
        out.println("<details><summary>The page looks old or does not update</summary><p>Press Ctrl + Shift + R to refresh "
                + "the page, or log out and log in again.</p></details>");
        out.println("<details><summary>Are the marks memo and bonafide certificate official?</summary><p>No. They are unofficial "
                + "sample previews. Ask the college office for a verified document.</p></details>");
        out.println("</div>");
        out.println("<a class='document-button' href='" + mailLink + "'>Report an issue by email</a>");
        out.println("</section>");

        /* Change Password */
        out.println("<section class='section' id='password'>");
        heading(out, "Change Password");
        out.println("<form method='post' action='Login'>");
        out.println("<input type='hidden' name='action' value='changePassword'>");
        out.println("<label>Current Password</label>");
        out.println("<input type='password' name='currentPassword' "
                + "required autocomplete='current-password'>");
        out.println("<label>New Password</label>");
        out.println("<input type='password' name='newPassword' "
                + "minlength='6' required autocomplete='new-password'>");
        out.println("<label>Confirm New Password</label>");
        out.println("<input type='password' name='confirmPassword' "
                + "minlength='6' required autocomplete='new-password'>");
        out.println("<button type='submit'>Update Password</button>");
        out.println("</form></section>");

        out.println("<div class='footer dashboard-only'>"
                + "Aditya College of Engineering &amp; Technology<br>"
                + "Department of CSE (AI&amp;ML) | Student Portal<br>"
                + "Need help? " + escapeHtml(SUPPORT_EMAIL) + " | " + escapeHtml(SUPPORT_HOURS) + "<br>"
                + "Academic Year 2026–27</div>");

        /* Live clock-based upcoming class card and page navigation. */
        out.println("<script>");

        out.println("var classSchedule = {");
        out.println("0: [],");
        out.println("1: [{s:'DAA',a:'09:10',b:'10:10'},{s:'SDE',a:'10:10',b:'11:10'},{s:'SDT',a:'11:20',b:'12:20'},{s:'OOPS',a:'12:20',b:'13:20'},{s:'DBMS',a:'14:05',b:'15:05'},{s:'I&E',a:'15:05',b:'16:05'}],");
        out.println("2: [{s:'DBMS LAB (B1) / OOPS LAB (B2)',a:'09:10',b:'11:10'},{s:'OOPS',a:'11:20',b:'12:20'},{s:'DAA',a:'12:20',b:'13:20'},{s:'SDT',a:'14:05',b:'15:05'},{s:'SPORTS',a:'15:05',b:'16:05'}],");
        out.println("3: [{s:'OOPS',a:'09:10',b:'10:10'},{s:'DBMS',a:'10:10',b:'11:10'},{s:'DAA',a:'11:20',b:'12:20'},{s:'SDE',a:'12:20',b:'13:20'},{s:'OOPS LAB (B1) / DBMS LAB (B2)',a:'14:05',b:'16:05'}],");
        out.println("4: [{s:'SDE',a:'09:10',b:'10:10'},{s:'I&E',a:'10:10',b:'11:10'},{s:'DBMS',a:'11:20',b:'12:20'},{s:'DAA',a:'12:20',b:'13:20'},{s:'CM LAB (B1) / DE&V LAB (B2)',a:'14:05',b:'16:05'}],");
        out.println("5: [{s:'CRT',a:'09:10',b:'10:10'},{s:'CRT',a:'10:10',b:'11:10'},{s:'CRT',a:'11:20',b:'12:20'},{s:'CRT',a:'12:20',b:'13:20'},{s:'CRT',a:'14:05',b:'15:05'},{s:'CRT',a:'15:05',b:'16:05'}],");
        out.println("6: [{s:'SDT',a:'09:10',b:'10:10'},{s:'SDE',a:'10:10',b:'11:10'},{s:'DE&V LAB (B1) / CM LAB (B2)',a:'11:20',b:'13:20'},{s:'OOPS',a:'14:05',b:'15:05'},{s:'LIB',a:'15:05',b:'16:05'}]");
        out.println("};");

        out.println("function timeToMinutes(value) { var parts=value.split(':'); return Number(parts[0])*60+Number(parts[1]); }");
        out.println("function clockLabel(value) { var p=value.split(':'); var h=Number(p[0]); var suffix=h>=12?'PM':'AM'; h=h%12||12; return h+':'+p[1]+' '+suffix; }");
        out.println("function updateUpcomingClass() {");
        out.println("var subject=document.getElementById('upcomingSubject'); var time=document.getElementById('upcomingTime'); var label=document.getElementById('upcomingLabel'); var status=document.getElementById('upcomingStatus');");
        out.println("if(!subject||!time||!label||!status) return;");
        out.println("var now=new Date(); var day=now.getDay(); var nowMins=now.getHours()*60+now.getMinutes(); var schedule=classSchedule[day]||[];");
        out.println("var current=null; var next=null;");
        out.println("for(var i=0;i<schedule.length;i++){var start=timeToMinutes(schedule[i].a);var end=timeToMinutes(schedule[i].b);if(nowMins>=start&&nowMins<end){current=schedule[i];break;}if(nowMins<start&&!next){next=schedule[i];}}");
        out.println("if(current){label.textContent='CLASS IN PROGRESS';subject.textContent=current.s;time.textContent=clockLabel(current.a)+' – '+clockLabel(current.b)+' · '+now.toLocaleDateString(undefined,{weekday:'long'});status.textContent='LIVE NOW · '+Math.max(0,timeToMinutes(current.b)-nowMins)+' MIN LEFT';return;}");
        out.println("if(next){label.textContent='UPCOMING CLASS';subject.textContent=next.s;time.textContent='Today, '+clockLabel(next.a)+' – '+clockLabel(next.b)+' · '+now.toLocaleDateString(undefined,{weekday:'long'});var delta=timeToMinutes(next.a)-nowMins;status.textContent=delta<=1?'STARTING SOON':'STARTS IN '+Math.floor(delta/60)+'H '+(delta%60)+'M';return;}");
        out.println("var dayNames=['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday']; var future=new Date(now); var first=null;");
        out.println("for(var add=1;add<=7;add++){future=new Date(now);future.setDate(now.getDate()+add);var futureSchedule=classSchedule[future.getDay()]||[];if(futureSchedule.length){first={day:future,period:futureSchedule[0]};break;}}");
        out.println("if(first){label.textContent='NEXT CLASS';subject.textContent=first.period.s;time.textContent=first.day.toLocaleDateString(undefined,{weekday:'long',month:'short',day:'numeric'})+' · '+clockLabel(first.period.a)+' – '+clockLabel(first.period.b);status.textContent='NEXT CLASS DAY';}else{label.textContent='UPCOMING CLASS';subject.textContent='No scheduled class found';time.textContent='Please check the full timetable.';status.textContent='TIMETABLE';}");
        out.println("}");

        out.println("function setSidebarCollapsed(collapsed) {");
        out.println("document.body.classList.toggle('sidebar-collapsed', collapsed);");
        out.println("var button=document.getElementById('sidebarToggle');");
        out.println("if(button){button.innerHTML=collapsed?'&#9776;':'&#10005;';"
                + "button.setAttribute('aria-label',collapsed?'Expand sidebar':'Collapse sidebar');"
                + "button.title=collapsed?'Expand sidebar':'Collapse sidebar';}");
        out.println("}");
        out.println("function toggleSidebar() {");
        out.println("setSidebarCollapsed(!document.body.classList.contains('sidebar-collapsed'));");
        out.println("}");
        out.println("/* Smooth hover expansion with a tiny close delay to prevent flicker. */");
        out.println("(function(){var sidebar=document.getElementById('portalSidebar');"
                + "if(!sidebar)return;var closeTimer=null;"
                + "var finePointer=window.matchMedia&&window.matchMedia('(hover: hover) and (pointer: fine)').matches;"
                + "if(finePointer){setSidebarCollapsed(true);"
                + "sidebar.addEventListener('mouseenter',function(){if(closeTimer){clearTimeout(closeTimer);closeTimer=null;}setSidebarCollapsed(false);});"
                + "sidebar.addEventListener('mouseleave',function(){closeTimer=setTimeout(function(){setSidebarCollapsed(true);},140);});"
                + "}else{setSidebarCollapsed(false);}"
                + "})();");

        out.println("function showPage(pageId, fromHistory) {");
        out.println("var mc0 = document.querySelector('.main-content');");
        out.println("var prevScroll = mc0 ? mc0.scrollTop : 0;");
        out.println("var wasDash = document.body.classList.contains('dashboard-view');");

        out.println("document.querySelectorAll('.section').forEach(function(section) {");
        out.println("    section.classList.remove('active');");
        out.println("});");

        out.println("document.querySelectorAll('.nav-link[data-page]').forEach(function(link) {");
        out.println("    link.classList.remove('active');");
        out.println("});");

        out.println("if (pageId === 'dashboard') {");
        out.println("    document.body.classList.add('dashboard-view');");
        out.println("} else {");
        out.println("    document.body.classList.remove('dashboard-view');");
        out.println("    var selectedSection = document.getElementById(pageId);");
        out.println("    if (selectedSection) {");
        out.println("        selectedSection.classList.add('active');");
        out.println("    }");
        out.println("}");

        out.println("document.querySelectorAll('.nav-link[data-page]').forEach(function(link) {");
        out.println("    if (link.getAttribute('data-page') === pageId) {");
        out.println("        link.classList.add('active');");
        out.println("    }");
        out.println("});");

        out.println("var mainContent = document.querySelector('.main-content');");
        out.println("if (mainContent) mainContent.scrollTop = 0;");
        out.println("if (!fromHistory && wasDash && pageId !== 'dashboard') {"
                + " history.replaceState({page: 'dashboard', scroll: prevScroll}, '', location.href); }");
        out.println("if (!fromHistory && !(history.state && history.state.page === pageId)) {"
                + " history.pushState({page: pageId}, '', '#' + pageId); }");
        out.println("return false;");
        out.println("}");

        out.println("function openStudentModal(button) {");
        out.println("var fields={studentModalName:'name',studentModalRoll:'roll',"
                + "studentModalDepartment:'department',studentModalSemester:'semester',"
                + "studentModalGender:'gender',studentModalAttendance:'attendance',studentModalMarks:'marks'};");
        out.println("Object.keys(fields).forEach(function(id){var el=document.getElementById(id);"
                + "if(el)el.textContent=button.getAttribute('data-'+fields[id])||'Not specified';});");
        out.println("var photo=document.getElementById('studentModalPhoto');"
                + "var photoPath=button.getAttribute('data-photo');"
                + "if(photo){photo.style.display=photoPath?'':'none';if(photoPath)photo.src=photoPath;}");
        out.println("var overlay=document.getElementById('studentModalOverlay');"
                + "if(overlay){overlay.classList.add('open');document.body.style.overflow='hidden';} ");
        out.println("}");
        out.println("function closeStudentModal(event) {");
        out.println("var overlay=document.getElementById('studentModalOverlay');if(!overlay)return;"
                + "if(event&&event.target!==overlay)return;overlay.classList.remove('open');"
                + "document.body.style.overflow='';");
        out.println("}");
        out.println("document.addEventListener('keydown',function(event){"
                + "if(event.key==='Escape')closeStudentModal();});");

        out.println("function searchStudents() {");
        out.println("    var input = document.getElementById('studentSearch');");
        out.println("    if (!input) return;");
        out.println("    var filter = input.value.toLowerCase();");
        out.println("    document.querySelectorAll('#studentsTable tbody tr').forEach(function(row) {");
        out.println("        row.style.display = row.innerText.toLowerCase().includes(filter) ? '' : 'none';");
        out.println("    });");
        out.println("}");

        out.println("var dailyThoughts = ["
                + "{text:'Success is the sum of small efforts repeated every day.',author:'Robert Collier'},"
                + "{text:'The secret of getting ahead is getting started.',author:'Mark Twain'},"
                + "{text:'It always seems impossible until it is done.',author:'Nelson Mandela'},"
                + "{text:'Great things are done by a series of small things brought together.',author:'Vincent van Gogh'},"
                + "{text:'Learning never exhausts the mind.',author:'Leonardo da Vinci'},"
                + "{text:'Believe you can and you are halfway there.',author:'Theodore Roosevelt'},"
                + "{text:'Dream big, start small, but most of all start.',author:'Simon Sinek'}];");
        out.println("var today = new Date(); var dayIndex = Math.floor((today - new Date(today.getFullYear(),0,0)) / 86400000) % dailyThoughts.length;");
        out.println("var thought = dailyThoughts[dayIndex];");
        out.println("var thoughtText = document.getElementById('dailyThought'); var thoughtAuthor = document.getElementById('dailyThoughtAuthor');");
        out.println("if(thoughtText && thoughtAuthor){thoughtText.textContent='\u201c'+thought.text+'\u201d';thoughtAuthor.textContent='— '+thought.author;}");
        out.println("function matchAccreditationWidth(){");
        out.println(" var name=document.querySelector('.college-name');");
        out.println(" var image=document.querySelector('.accreditation-logo');");
        out.println(" var holder=document.querySelector('.accreditation-center');");
        out.println(" if(!name||!image||!holder)return;");
        out.println(" var range=document.createRange(); range.selectNodeContents(name);");
        out.println(" var width=Math.ceil(range.getBoundingClientRect().width);");
        out.println(" if(width>0){image.style.width=width+'px';holder.style.width=width+'px';}");
        out.println("}");
        out.println("matchAccreditationWidth();");
        out.println("window.addEventListener('resize',matchAccreditationWidth);");
        out.println("if(window.ResizeObserver){new ResizeObserver(matchAccreditationWidth).observe(document.querySelector('.college-name'));}");
        out.println("var HOLIDAYS={");
        out.println("'2026-01-14':['Bhogi','&#128293;'],'2026-01-15':['Sankranti / Pongal','&#129665;'],'2026-01-26':['Republic Day','&#127894;'],");
        out.println("'2026-02-15':['Maha Shivaratri','&#128305;'],'2026-03-03':['Holi','&#127912;'],'2026-03-19':['Ugadi','&#127807;'],");
        out.println("'2026-03-21':['Eid-ul-Fitr','&#127769;'],'2026-03-27':['Sri Rama Navami','&#127993;'],'2026-04-03':['Good Friday','&#10013;'],");
        out.println("'2026-04-14':['Ambedkar Jayanti','&#128216;'],'2026-05-27':['Eid-ul-Azha','&#127769;'],'2026-06-02':['Telangana Formation Day','&#127963;'],");
        out.println("'2026-06-26':['Muharram','&#128332;'],'2026-08-10':['Bonalu','&#129684;'],'2026-08-15':['Independence Day','&#127878;'],");
        out.println("'2026-08-26':['Eid Milad-un-Nabi','&#128332;'],'2026-09-04':['Sri Krishna Astami','&#129434;'],'2026-09-14':['Vinayaka Chavithi','&#128024;'],");
        out.println("'2026-10-02':['Gandhi Jayanti','&#128330;'],'2026-10-18':['Saddula Bathukamma','&#127804;'],'2026-10-20':['Vijaya Dasami (Dussehra)','&#127989;'],");
        out.println("'2026-10-21':['Day after Dussehra','&#127882;'],'2026-11-08':['Deepavali','&#129684;'],'2026-11-24':['Kartika Purnima','&#127765;'],");
        out.println("'2026-12-25':['Christmas','&#127876;'],'2026-12-26':['Boxing Day','&#127873;']");
        out.println("};");
        out.println("var MONTHS=['January','February','March','April','May','June','July','August','September','October','November','December'];");
        out.println("var calNow=new Date(), calYear=calNow.getFullYear(), calMonth=calNow.getMonth();");
        out.println("function p2(n){return n<10?'0'+n:''+n;}");
        out.println("function holidayOn(y,m,d){return HOLIDAYS[y+'-'+p2(m+1)+'-'+p2(d)]||null;}");
        out.println("function dayDiff(y,m,d){var t=new Date();t.setHours(0,0,0,0);return Math.round((new Date(y,m,d)-t)/86400000);}");
        out.println("function whenText(n){return n===0?'Today':n===1?'Tomorrow':n>1?'In '+n+' days':Math.abs(n)+(n===-1?' day ago':' days ago');}");
        out.println("function nextHolidays(count){var list=[],t=new Date();for(var i=0;i<400&&list.length<count;i++){var d=new Date(t.getFullYear(),t.getMonth(),t.getDate()+i);var h=holidayOn(d.getFullYear(),d.getMonth(),d.getDate());if(h)list.push({h:h,d:d,n:i});}return list;}");
        out.println("function renderCalendar(){");
        out.println("var grid=document.getElementById('calGrid');if(!grid)return;");
        out.println("document.getElementById('calTitle').textContent=MONTHS[calMonth]+' '+calYear;");
        out.println("var first=new Date(calYear,calMonth,1).getDay(),days=new Date(calYear,calMonth+1,0).getDate(),t=new Date(),html='',count=0;");
        out.println("for(var i=0;i<first;i++)html+='<div class=\"cal-cell cal-empty\"></div>';");
        out.println("for(var d=1;d<=days;d++){");
        out.println("var h=holidayOn(calYear,calMonth,d),dow=new Date(calYear,calMonth,d).getDay();");
        out.println("var isToday=(d===t.getDate()&&calMonth===t.getMonth()&&calYear===t.getFullYear());");
        out.println("if(h)count++;");
        out.println("html+='<div class=\"cal-cell'+(h?' cal-holiday':'')+(dow===0?' cal-sunday':'')+(isToday?' cal-today':'')+'\" data-d=\"'+d+'\" tabindex=\"0\"><span class=\"cal-num\">'+d+'</span>'+(h?'<span class=\"cal-emoji\">'+h[1]+'</span><span class=\"cal-name\">'+h[0]+'</span>':(dow===0?'<span class=\"cal-emoji\">&#127796;</span>':''))+'</div>';");
        out.println("}");
        out.println("grid.innerHTML=html;");
        out.println("document.getElementById('calCount').textContent=count?count+' holiday'+(count>1?'s':'')+' this month':'No listed holidays this month';");
        out.println("}");
        out.println("function showDay(d){");
        out.println("var el=document.getElementById('calDetail');if(!el)return;");
        out.println("var dt=new Date(calYear,calMonth,d),h=holidayOn(calYear,calMonth,d),dow=dt.getDay(),n=dayDiff(calYear,calMonth,d);");
        out.println("var label=dt.toLocaleDateString('en-IN',{weekday:'long',day:'numeric',month:'long',year:'numeric'});");
        out.println("var cls='',emoji,title,text;");
        out.println("if(h){cls='cal-d-holiday';emoji=h[1];title=h[0];text='Holiday &ndash; no classes. Enjoy the day!';}");
        out.println("else if(dow===0){cls='cal-d-sunday';emoji='&#127796;';title='Sunday &ndash; weekly off';text='Rest, recharge and revise.';}");
        out.println("else{emoji='&#128218;';title='Regular working day';text=((classSchedule[dow]||[]).length)+' class blocks in the timetable.';}");
        out.println("el.className='cal-detail '+cls;");
        out.println("el.innerHTML='<div class=\"cal-d-emoji\">'+emoji+'</div><div><div class=\"cal-d-title\">'+title+'</div><div class=\"cal-d-text\">'+label+' &middot; '+whenText(n)+'<br>'+text+'</div></div>';");
        out.println("}");
        out.println("function renderHero(){");
        out.println("var el=document.getElementById('calHero');if(!el)return;");
        out.println("var t=new Date(),h=holidayOn(t.getFullYear(),t.getMonth(),t.getDate()),dow=t.getDay(),nx=nextHolidays(2),nxt=null,c='';");
        out.println("for(var i=0;i<nx.length;i++){if(nx[i].n>0){nxt=nx[i];break;}}");
        out.println("var nextTxt=nxt?'Next holiday: <b>'+nxt.h[0]+'</b> '+nxt.h[1]+' &middot; '+whenText(nxt.n).toLowerCase():'';");
        out.println("var cls,label,title,emoji,text;");
        out.println("if(h){cls='cal-hero cal-hero-holiday';label='TODAY IS A HOLIDAY';title=h[0];emoji=h[1];text='No classes today &ndash; enjoy the day!';");
        out.println("for(var k=0;k<14;k++)c+='<span style=\"left:'+(k*7+2)+'%;animation-delay:'+((k%5)*0.6)+'s\">'+['&#127881;','&#10024;','&#127882;'][k%3]+'</span>';}");
        out.println("else if(dow===0){cls='cal-hero cal-hero-sunday';label='SUNDAY';title='Weekly off';emoji='&#127796;';text='No classes today. Rest, recharge and revise.';}");
        out.println("else{cls='cal-hero';label='REGULAR WORKING DAY';title=t.toLocaleDateString('en-IN',{weekday:'long',day:'numeric',month:'short'});emoji='&#128218;';text='Classes are running as per the timetable.';}");
        out.println("el.className=cls;");
        out.println("el.innerHTML='<div class=\"confetti\">'+c+'</div><div class=\"cal-hero-emoji\">'+emoji+'</div><div><div class=\"cal-hero-label\">'+label+'</div><div class=\"cal-hero-title\">'+title+'</div><div class=\"cal-hero-text\">'+text+(nextTxt?'<br>'+nextTxt:'')+'</div></div>';");
        out.println("}");
        out.println("function renderUpcoming(){");
        out.println("var el=document.getElementById('calUpcoming');if(!el)return;");
        out.println("var list=nextHolidays(5),html='';");
        out.println("for(var i=0;i<list.length;i++){var x=list[i];html+='<div class=\"cal-up\"><span class=\"cal-up-emoji\">'+x.h[1]+'</span><div class=\"cal-up-main\"><b>'+x.h[0]+'</b><small>'+x.d.toLocaleDateString('en-IN',{weekday:'short',day:'numeric',month:'short'})+'</small></div><span class=\"cal-up-when\">'+whenText(x.n)+'</span></div>';}");
        out.println("el.innerHTML=html||'<p class=\"placeholder\">No upcoming holidays are listed.</p>';");
        out.println("}");
        out.println("function calMove(step){calMonth+=step;if(calMonth<0){calMonth=11;calYear--;}if(calMonth>11){calMonth=0;calYear++;}renderCalendar();}");
        out.println("function calGoToday(){var t=new Date();calYear=t.getFullYear();calMonth=t.getMonth();renderCalendar();showDay(t.getDate());}");
        out.println("(function(){var g=document.getElementById('calGrid');if(!g)return;");
        out.println("function pick(e){var c=e.target.closest('.cal-cell');if(!c||!c.getAttribute('data-d'))return;var o=g.querySelector('.cal-sel');if(o)o.classList.remove('cal-sel');c.classList.add('cal-sel');showDay(Number(c.getAttribute('data-d')));}");
        out.println("g.addEventListener('click',pick);");
        out.println("g.addEventListener('keydown',function(e){if(e.key==='Enter')pick(e);});})();");
        out.println("renderCalendar();renderHero();renderUpcoming();showDay(new Date().getDate());");
        out.println("window.setInterval(renderHero,3600000);");

        /* Reopen the same page after refresh (reads #timetable etc. from the URL). */
        out.println("var startPage = (location.hash || '').replace('#', '');");
        out.println("var startEl = document.getElementById(startPage);");
        out.println("if (!startEl || !startEl.classList.contains('section')) { startPage = 'dashboard'; }");
        out.println("history.replaceState({page: startPage}, '', location.pathname + location.search"
                + " + (startPage === 'dashboard' ? '' : '#' + startPage));");
        out.println("window.addEventListener('popstate', function (e) {"
                + " var st = e.state || {}; var pg = st.page || 'dashboard'; showPage(pg, true);"
                + " if (pg === 'dashboard' && typeof st.scroll === 'number') {"
                + " var mc = document.querySelector('.main-content');"
                + " window.requestAnimationFrame(function () { if (mc) mc.scrollTop = st.scroll; }); } });");
        /* If Chrome restores an old copy of this page from its back/forward cache, reload it
           so the server can check the session (after logout this sends you to the login page). */
        out.println("window.addEventListener('pageshow', function (e) {"
                + " if (e.persisted) { location.reload(); } });");
        out.println("showPage(startPage, true);");
        out.println("updateUpcomingClass();");
        out.println("window.setInterval(updateUpcomingClass, 30000);");
        out.println("</script>");

        out.println("</div></main></body></html>");
    }

    private void renderAttendanceChart(PrintWriter out, String attendance) {
        double mine;
        try {
            mine = Double.parseDouble(attendance.trim());
        } catch (NumberFormatException e) {
            out.println("<div class='att-card'><p class='placeholder'>"
                    + "The attendance chart is not available because no "
                    + "attendance value is stored for this student.</p></div>");
            return;
        }

        double average = -1;
        double highest = -1;

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(
                        "SELECT AVG(attendance) AS avg_att, "
                        + "MAX(attendance) AS max_att FROM students");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                average = rs.getDouble("avg_att");
                highest = rs.getDouble("max_att");
            }
        } catch (SQLException e) {
            log("Unable to load attendance statistics", e);
        }

        final double required = 75.0;
        StringBuilder bars = new StringBuilder();
        StringBuilder names = new StringBuilder();

        attBar(bars, names, "bar-me", "My Attendance", mine);
        if (average >= 0) {
            attBar(bars, names, "bar-avg", "Class Average", average);
        }
        if (highest >= 0) {
            attBar(bars, names, "bar-top", "Class Highest", highest);
        }

        String mineText = String.format(java.util.Locale.ROOT, "%.1f", mine);
        String statusClass;
        String statusText;

        if (mine >= required) {
            statusClass = "att-ok";
            statusText = "&#10004; Your attendance of " + mineText
                    + "% meets the 75% minimum requirement. You are "
                    + String.format(java.util.Locale.ROOT, "%.1f", mine - required)
                    + "% above the minimum.";
        } else {
            statusClass = "att-low";
            statusText = "&#9888; Your attendance of " + mineText
                    + "% is below the 75% minimum. You are short by "
                    + String.format(java.util.Locale.ROOT, "%.1f", required - mine)
                    + "%. Attend classes regularly to avoid an attendance shortage.";
        }

        out.println("<div class='att-card'>");
        out.println("<h3 class='att-title'>Attendance Comparison</h3>");
        out.println("<p class='att-sub'>Your attendance compared with the class "
                + "average and the highest in the class. The dashed line marks "
                + "the usual 75% minimum.</p>");
        out.println("<div class='att-plot'>");
        out.println("<span class='att-zero'>0%</span>");
        out.println("<div class='att-grid' style='bottom:25%'><span>25%</span></div>");
        out.println("<div class='att-grid' style='bottom:50%'><span>50%</span></div>");
        out.println("<div class='att-grid' style='bottom:100%'><span>100%</span></div>");
        out.println("<div class='att-line' style='bottom:75%'><span>Minimum 75%</span></div>");
        out.println("<div class='att-bars'>" + bars + "</div>");
        out.println("</div>");
        out.println("<div class='att-labels'>" + names + "</div>");
        out.println("<div class='att-status " + statusClass + "'>" + statusText + "</div>");
        out.println("</div>");
    }

    private void attBar(StringBuilder bars, StringBuilder names,
            String css, String label, double value) {
        double height = Math.max(0, Math.min(100, value));
        String text = String.format(java.util.Locale.ROOT, "%.1f", value) + "%";
        String h = String.format(java.util.Locale.ROOT, "%.1f", height);

        bars.append("<div class='att-col'><div class='att-bar ")
                .append(css).append("' style='height:").append(h)
                .append("%'><b>").append(text).append("</b></div></div>");
        names.append("<div>").append(escapeHtml(label)).append("</div>");
    }

    private void renderMarksChart(PrintWriter out, String marks, String grade) {
        double mine;
        try {
            mine = Double.parseDouble(marks.trim());
        } catch (NumberFormatException e) {
            out.println("<div class='att-card'><p class='placeholder'>"
                    + "The marks chart is not available because no marks / "
                    + "SGPA value is stored for this student.</p></div>");
            return;
        }

        double average = -1;
        double highest = -1;

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(
                        "SELECT AVG(marks) AS avg_m, "
                        + "MAX(marks) AS max_m FROM students");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                average = rs.getDouble("avg_m");
                highest = rs.getDouble("max_m");
            }
        } catch (SQLException e) {
            log("Unable to load marks statistics", e);
        }

        StringBuilder bars = new StringBuilder();
        StringBuilder names = new StringBuilder();

        scoreBar(bars, names, "bar-me", "My SGPA", mine);
        if (average >= 0) {
            scoreBar(bars, names, "bar-avg", "Class Average", average);
        }
        if (highest >= 0) {
            scoreBar(bars, names, "bar-top", "Class Highest", highest);
        }

        String mineText = String.format(java.util.Locale.ROOT, "%.2f", mine);
        String statusText = "Your SGPA is " + mineText;

        if (grade != null && !grade.isEmpty()) {
            statusText += " and your grade is " + escapeHtml(grade);
        }
        statusText += ".";

        if (average >= 0) {
            double diff = mine - average;
            String diffText = String.format(java.util.Locale.ROOT,
                    "%.2f", Math.abs(diff));
            String avgText = String.format(java.util.Locale.ROOT,
                    "%.2f", average);
            if (diff >= 0) {
                statusText += " That is " + diffText
                        + " above the class average of " + avgText + ".";
            } else {
                statusText += " That is " + diffText
                        + " below the class average of " + avgText + ".";
            }
        }

        out.println("<div class='att-card'>");
        out.println("<h3 class='att-title'>Marks / SGPA Comparison</h3>");
        out.println("<p class='att-sub'>Your SGPA compared with the class "
                + "average and the highest in the class (scale: 0 to 10).</p>");
        out.println("<div class='att-plot'>");
        out.println("<span class='att-zero'>0</span>");
        out.println("<div class='att-grid' style='bottom:25%'><span>2.5</span></div>");
        out.println("<div class='att-grid' style='bottom:50%'><span>5</span></div>");
        out.println("<div class='att-grid' style='bottom:75%'><span>7.5</span></div>");
        out.println("<div class='att-grid' style='bottom:100%'><span>10</span></div>");
        out.println("<div class='att-bars'>" + bars + "</div>");
        out.println("</div>");
        out.println("<div class='att-labels'>" + names + "</div>");
        out.println("<div class='att-status att-info'>" + statusText + "</div>");
        out.println("</div>");
    }

    private void scoreBar(StringBuilder bars, StringBuilder names,
            String css, String label, double value) {
        double height = Math.max(0, Math.min(10, value)) * 10;
        String text = String.format(java.util.Locale.ROOT, "%.2f", value);
        String h = String.format(java.util.Locale.ROOT, "%.1f", height);

        bars.append("<div class='att-col'><div class='att-bar ")
                .append(css).append("' style='height:").append(h)
                .append("%'><b>").append(text).append("</b></div></div>");
        names.append("<div>").append(escapeHtml(label)).append("</div>");
    }

    private void renderToppers(PrintWriter out, String myRoll, String myMarks) {
        StringBuilder cards = new StringBuilder();
        String sql = "SELECT full_name, roll_number, marks, grade, gender "
                + "FROM students WHERE marks IS NOT NULL "
                + "ORDER BY marks DESC, roll_number ASC LIMIT 3";

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            String[] medals = {"&#129351;", "&#129352;", "&#129353;"};
            int position = 0;

            while (rs.next() && position < 3) {
                String roll = getValue(rs, "roll_number").trim();
                String photo = photoFor(roll, getValue(rs, "gender"));

                String grade = getValue(rs, "grade");
                boolean isMe = roll.equalsIgnoreCase(myRoll == null ? "" : myRoll.trim());

                cards.append("<div class='topper rank").append(position + 1).append("'>")
                        .append("<div class='topper-photo'><img src='images/").append(photo)
                        .append("' alt='' onerror=\"this.style.visibility='hidden'\">")
                        .append("<span class='medal'>").append(medals[position]).append("</span></div>")
                        .append("<div class='topper-name'>")
                        .append(escapeHtml(getValue(rs, "full_name")))
                        .append(isMe ? "<span class='topper-you'>YOU</span>" : "")
                        .append("</div><div class='topper-roll'>").append(escapeHtml(roll))
                        .append("</div><div class='topper-score'>")
                        .append(escapeHtml(getValue(rs, "marks")))
                        .append(" <small>SGPA</small></div>")
                        .append(grade.isEmpty() ? ""
                                : "<div class='topper-grade'>Grade " + escapeHtml(grade) + "</div>")
                        .append("</div>");
                position++;
            }
        } catch (SQLException e) {
            log("Unable to load toppers", e);
            return;
        }

        if (cards.length() == 0) {
            return;
        }

        int rank = 0;
        int total = 0;
        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(
                        "SELECT (SELECT COUNT(*) + 1 FROM students WHERE marks > ?) AS my_rank, "
                        + "(SELECT COUNT(*) FROM students) AS total")) {
            ps.setBigDecimal(1, new java.math.BigDecimal(myMarks.trim()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    rank = rs.getInt("my_rank");
                    total = rs.getInt("total");
                }
            }
        } catch (SQLException | NumberFormatException e) {
            log("Unable to calculate rank", e);
        }

        out.println("<section class='toppers-card dashboard-only' aria-label='Class toppers'>");
        out.println("<div class='toppers-head'><div>"
                + "<h3 class='toppers-title'>&#127942; Class Toppers</h3>"
                + "<p class='toppers-sub'>Top 3 students by SGPA</p></div>"
                + (rank > 0 ? "<div class='rank-pill'>Your rank: #" + rank
                        + " of " + total + "</div>" : "")
                + "</div>");
        out.println("<div class='podium'>" + cards + "</div>");
        out.println("<p class='toppers-note'>Ranks are based on the SGPA stored in "
                + "the portal. Students with equal SGPA share a rank and are "
                + "listed by roll number.</p>");
        out.println("</section>");
    }

    private String inr(int amount) {
        String s = Integer.toString(amount);
        if (s.length() <= 3) {
            return "&#8377;" + s;
        }
        String last3 = s.substring(s.length() - 3);
        String rest = s.substring(0, s.length() - 3);
        StringBuilder sb = new StringBuilder();
        while (rest.length() > 2) {
            sb.insert(0, "," + rest.substring(rest.length() - 2));
            rest = rest.substring(0, rest.length() - 2);
        }
        sb.insert(0, rest);
        return "&#8377;" + sb + "," + last3;
    }

    private String feeStat(String css, String label, String value, String note) {
        return "<div class='fee-stat " + css + "'><small>" + label + "</small><b>"
                + value + "</b><span>" + note + "</span></div>";
    }

    /** Sample fee data for the demo: about 1.2 lakh per year, status chosen from the roll number. */
    private void renderFees(PrintWriter out, String rollNumber) {
        final int annual = 120000;
        String r = rollNumber == null ? "" : rollNumber.trim().toUpperCase(java.util.Locale.ROOT);
        boolean govt = r.equals("24R01A6656");
        int mode = (r.hashCode() & 0x7fffffff) % 4;

        int paid;
        String heroClass;
        String emoji;
        String title;
        String text;
        String nextDue;
        int[] amounts;
        boolean[] done;
        String[] dues;

        if (govt) {
            paid = annual;
            heroClass = "fee-hero fee-hero-gov";
            emoji = "&#127963;";
            title = "Your fees are fully sponsored!";
            text = "The Government of Telangana pays your tuition fee under the Full Fee "
                    + "Reimbursement scheme. You have nothing to pay this year, so just focus "
                    + "on learning and making everyone proud.";
            nextDue = "Nothing due";
            amounts = new int[0];
            done = new boolean[0];
            dues = new String[0];
        } else if (mode == 0) {
            paid = annual;
            heroClass = "fee-hero";
            emoji = "&#127881;";
            title = "All fees paid, you are all set!";
            text = "Your 2nd year fee of " + inr(annual) + " is paid in full. "
                    + "Thank you for paying on time!";
            nextDue = "Nothing due";
            amounts = new int[]{annual};
            done = new boolean[]{true};
            dues = new String[]{"Paid in full"};
        } else if (mode == 1) {
            paid = 60000;
            heroClass = "fee-hero fee-hero-mid";
            emoji = "&#128522;";
            title = "Halfway there!";
            text = "You have paid " + inr(60000) + " of " + inr(annual) + ". Only "
                    + inr(60000) + " is left, due by 30 Nov 2026.";
            nextDue = "30 Nov 2026";
            amounts = new int[]{60000, 60000};
            done = new boolean[]{true, false};
            dues = new String[]{"Paid", "Due 30 Nov 2026"};
        } else if (mode == 2) {
            paid = 80000;
            heroClass = "fee-hero fee-hero-mid";
            emoji = "&#128079;";
            title = "Almost done!";
            text = "Great going! You have paid " + inr(80000) + " of " + inr(annual)
                    + ". Just " + inr(40000) + " to go, due by 28 Feb 2027.";
            nextDue = "28 Feb 2027";
            amounts = new int[]{40000, 40000, 40000};
            done = new boolean[]{true, true, false};
            dues = new String[]{"Paid", "Paid", "Due 28 Feb 2027"};
        } else {
            paid = 40000;
            heroClass = "fee-hero fee-hero-mid";
            emoji = "&#127793;";
            title = "A good start!";
            text = "You have paid " + inr(40000) + " of " + inr(annual) + ". Two installments of "
                    + inr(40000) + " remain, due on 30 Nov 2026 and 28 Feb 2027.";
            nextDue = "30 Nov 2026";
            amounts = new int[]{40000, 40000, 40000};
            done = new boolean[]{true, false, false};
            dues = new String[]{"Paid", "Due 30 Nov 2026", "Due 28 Feb 2027"};
        }

        int balance = annual - paid;
        int percent = paid * 100 / annual;

        out.println("<div class='section-subtitle'>Your fee details for the 2nd year (2026&ndash;27), "
                + "with the rules and helpful information in one place.</div>");

        out.println("<div class='" + heroClass + "'><div class='fee-hero-emoji'>" + emoji + "</div><div>"
                + "<div class='fee-hero-label'>"
                + (govt ? "GOVERNMENT SPONSORED" : balance == 0 ? "FEES CLEARED" : "FEE STATUS") + "</div>"
                + "<div class='fee-hero-title'>" + title + "</div>"
                + "<div class='fee-hero-text'>" + text + "</div></div></div>");

        out.println("<div class='fee-stats'>");
        out.println(feeStat("fee-s-blue", "Annual Fee", inr(annual), "2nd year, 2026&ndash;27"));
        out.println(feeStat("fee-s-green", govt ? "Paid by Govt. of Telangana" : "Amount Paid",
                inr(paid), percent + "% of the annual fee"));
        out.println(feeStat("fee-s-orange", "Balance", govt ? "&#8377;0" : inr(balance),
                govt ? "Nothing to pay" : balance == 0 ? "All clear!" : "Still to pay"));
        out.println(feeStat("fee-s-violet", "Next Due Date", nextDue,
                govt ? "Reimbursement active" : balance == 0 ? "Enjoy your year" : "Please pay on time"));
        out.println("</div>");

        out.println("<div class='fee-progress'><div class='fee-progress-head'><b>Payment progress</b>"
                + "<span>" + percent + "% paid</span></div>"
                + "<div class='fee-bar'><div class='fee-bar-fill" + (govt ? " fee-fill-gov" : "")
                + "' style='width:" + percent + "%'></div></div>"
                + "<div class='fee-progress-foot'><span>" + inr(0) + "</span><span>"
                + inr(annual) + "</span></div></div>");

        out.println("<h3 class='fee-title'>Year-wise fee summary</h3>");
        out.println("<div class='table-wrap'><table class='course-table'><thead><tr><th>Year</th>"
                + "<th>Fee</th><th>Paid by</th><th>Status</th></tr></thead><tbody>");
        out.println("<tr><td>1st Year</td><td>" + inr(annual) + "</td><td>"
                + (govt ? "Govt. of Telangana" : "Student") + "</td><td><span class='chip "
                + (govt ? "chip-gov'>Reimbursed" : "chip-ok'>Paid in full") + "</span></td></tr>");
        out.println("<tr><td>2nd Year (2026&ndash;27)</td><td>" + inr(annual) + "</td><td>"
                + (govt ? "Govt. of Telangana" : "Student") + "</td><td><span class='chip "
                + (govt ? "chip-gov'>Reimbursed" : balance == 0 ? "chip-ok'>Paid in full"
                        : "chip-due'>" + inr(balance) + " pending") + "</span></td></tr>");
        out.println("</tbody></table></div>");

        if (govt) {
            out.println("<div class='fee-gov'><span class='fee-gov-badge'>&#127963; FULL FEE REIMBURSEMENT</span>"
                    + "<h3>Fee paid by the Government of Telangana</h3>"
                    + "<p>You are an eligible student under the Telangana Government's Full Fee "
                    + "Reimbursement scheme, so your tuition fee is paid directly to the college by "
                    + "the State Government. You do not need to pay anything yourself.</p>"
                    + "<ul><li>&#9989; Tuition fee: fully covered</li>"
                    + "<li>&#9989; Your payable amount: &#8377;0</li>"
                    + "<li>&#128221; Renew your ePASS application every academic year</li>"
                    + "<li>&#127891; Keep your attendance and studies strong to stay eligible</li></ul></div>");
        } else {
            out.println("<h3 class='fee-title'>" + (amounts.length == 1 ? "Payment" : "Installment plan") + "</h3>");
            out.println("<div class='fee-plan'>");
            for (int i = 0; i < amounts.length; i++) {
                out.println("<div class='fee-inst " + (done[i] ? "fee-inst-ok" : "fee-inst-due") + "'>"
                        + "<div class='fee-inst-no'>" + (amounts.length == 1 ? "Full payment" : "Installment " + (i + 1)) + "</div>"
                        + "<div class='fee-inst-amt'>" + inr(amounts[i]) + "</div>"
                        + "<div class='fee-inst-st'>" + (done[i] ? "&#10004; " : "&#9203; ") + dues[i] + "</div></div>");
            }
            out.println("</div>");
        }

        out.println("<h3 class='fee-title'>Good to know</h3>");
        out.println("<div class='fee-info-grid'>");
        out.println("<div class='fee-info fee-i-rules'><h3>&#128220; Fee Rules at a Glance</h3><ul>"
                + "<li>Pay each installment on or before its due date.</li>"
                + "<li>Keep every payment receipt safe. You may need it for certificates and scholarships.</li>"
                + "<li>A late fee may apply after the due date, as per college rules.</li>"
                + "<li>Fees once paid are generally not refundable. Confirm with the accounts office.</li>"
                + "<li>Unpaid dues can hold back hall tickets and certificates, so clear them early.</li></ul>"
                + "<p class='fee-note'>General guidelines for this demo. Please confirm the exact rules with the accounts office.</p></div>");
        out.println("<div class='fee-info fee-i-pay'><h3>&#128179; How to Pay</h3><ol>"
                + "<li>Visit the college accounts office with your roll number.</li>"
                + "<li>Pay by the mode accepted by the college.</li>"
                + "<li>Collect your receipt and keep a photo of it.</li></ol>"
                + "<p><span class='chip chip-gov'>Online payment: coming soon to this portal</span></p></div>");
        out.println("<div class='fee-info fee-i-gov'><h3>&#127963; Telangana Fee Reimbursement</h3>"
                + "<p>Eligible students can get tuition fees reimbursed by the Telangana Government through ePASS. "
                + "Apply or renew every year with your Aadhaar, bank details and income and caste certificates.</p>"
                + "<p><a href='https://telanganaepass.cgg.gov.in/' target='_blank' rel='noopener noreferrer'>"
                + "Open the Telangana ePASS portal &#8599;</a></p></div>");
        out.println("<div class='fee-info fee-i-tip'><h3>&#128161; Happy Tips</h3><ul>"
                + "<li>Set a phone reminder 7 days before each due date.</li>"
                + "<li>Save a photo of every receipt in one album.</li>"
                + "<li>The accounts office is closed on Sundays and holidays. Check the "
                + "<a href='#calendar' onclick=\"return showPage('calendar')\">Holiday Calendar</a> before you go.</li></ul></div>");
        out.println("<div class='fee-info fee-i-help'><h3>&#128222; Questions about fees?</h3>"
                + "<p>Talk to the accounts office, or reach the portal team from the "
                + "<a href='#support' onclick=\"return showPage('support')\">Help &amp; Support</a> page. "
                + "Please quote your roll number.</p></div>");
        out.println("</div>");

        out.println("<p class='fee-demo'><b>Demo notice:</b> the amounts, installments and due dates on this page "
                + "are sample data for the project demonstration. They are not actual college fee records.</p>");
    }

    private void nav(PrintWriter out, String id, String label) {
        String icon;
        switch (id) {
            case "dashboard":
                icon = "&#127968;";
                break;
            case "profile":
                icon = "&#128100;";
                break;
            case "students":
                icon = "&#128101;";
                break;
            case "academics":
                icon = "&#127891;";
                break;
            case "timetable":
                icon = "&#128197;";
                break;
            case "attendance":
                icon = "&#9989;";
                break;
            case "results":
                icon = "&#127919;";
                break;
            case "announcements":
                icon = "&#128227;";
                break;
            case "fees":
                icon = "&#128176;";
                break;
            case "documents":
                icon = "&#128196;";
                break;
            case "mentors":
                icon = "&#129309;";
                break;
            case "calendar":
                icon = "&#127881;";
                break;
            case "support":
                icon = "&#127911;";
                break;
            case "password":
                icon = "&#128274;";
                break;
            default:
                icon = "&#128196;";
        }
        out.println("<a class='nav-link' href='#" + id
                + "' data-page='" + id
                + "' onclick=\"return showPage('" + id + "')\">"
                + "<span class='nav-icon' aria-hidden='true'>" + icon + "</span>"
                + "<span>" + escapeHtml(label) + "</span></a>");
    }

    private void quickCard(PrintWriter out, String pageId, String icon,
            String title, String description) {
        out.println("<button type='button' class='quick-card' "
                + "onclick=\"return showPage('" + pageId + "')\" "
                + "aria-label='Open " + escapeHtml(title) + "'>");
        out.println("<span class='quick-icon' aria-hidden='true'>" + icon + "</span>");
        out.println("<span class='quick-name'>" + escapeHtml(title) + "</span>");
        out.println("<span class='quick-description'>" + escapeHtml(description) + "</span>");
        out.println("</button>");
    }

    private void stat(PrintWriter out, String label, String value,
            String pageId) {
        out.println("<a class='stat-card stat-link' href='#" + pageId
                + "' onclick=\"return showPage('" + pageId + "')\" "
                + "aria-label='Open " + escapeHtml(label) + " details'>");
        out.println("<div class='stat-label'>"
                + escapeHtml(label) + "</div>");
        out.println("<div class='stat-value'>"
                + escapeHtml(value) + "</div>");
        out.println("<div class='stat-more'>View details &#8594;</div></a>");
    }

    private void heading(PrintWriter out, String text) {
        out.println("<div class='section-heading'>"
                + escapeHtml(text) + "</div>");
    }

    private void detail(PrintWriter out, String label, String value) {
        out.println("<div class='detail'><div class='detail-label'>"
                + escapeHtml(label) + "</div><div class='detail-value'>"
                + escapeHtml(value == null || value.isEmpty()
                        ? "Not available" : value)
                + "</div></div>");
    }

    private void timetableCell(PrintWriter out, String value) {
        String css = value.contains("LAB") ? "lab-cell" : "";
        out.println("<td class='" + css + "'>" + value + "</td>");
    }

    private void course(PrintWriter out, String code,
            String name, String faculty, String emailUsernames) {
        out.println("<tr><td>" + escapeHtml(code) + "</td><td>"
                + escapeHtml(name) + "</td><td>"
                + escapeHtml(faculty) + "</td><td>");

        String[] emailList = emailUsernames.split(";");
        for (int i = 0; i < emailList.length; i++) {
            String email = emailList[i].trim() + "@acet.edu.in";
            if (i > 0) {
                out.println("<br>");
            }
            out.println("<a href='mailto:" + escapeHtml(email) + "'>"
                    + escapeHtml(email) + "</a>");
        }

        out.println("</td></tr>");
    }

    private void showMessage(HttpServletResponse response,
            String message, boolean success) throws IOException {

        response.setContentType("text/html;charset=UTF-8");

        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html><html><head>");
            out.println("<meta charset='UTF-8'>");
            out.println("<title>ACET Student Portal</title><style>");
            out.println("body{font-family:Arial,Helvetica,sans-serif;"
                    + "background:#e9eef8;text-align:center;padding:60px 15px;}");
            out.println(".box{background:white;max-width:450px;margin:auto;"
                    + "padding:30px;border-radius:18px;"
                    + "box-shadow:0 8px 25px rgba(26,48,90,.1);}");
            out.println("a{display:inline-block;margin-top:15px;"
                    + "color:#2456c7;text-decoration:none;}");
            out.println("</style></head><body><div class='box'>");
            out.println("<h2>" + (success ? "Success" : "Notice") + "</h2>");
            out.println("<p>" + escapeHtml(message) + "</p>");
            out.println("<a href='index.html'>Return to Login</a>");
            out.println("</div></body></html>");
        }
    }

    private String getValue(ResultSet rs, String column) {
        try {
            String value = rs.getString(column);
            return value == null ? "" : value;
        } catch (SQLException e) {
            return "";
        }
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }

        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}