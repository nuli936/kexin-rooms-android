import Foundation
import Security
import SwiftSoup

final class SchoolClient: NSObject, URLSessionTaskDelegate {
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = true
        config.httpCookieAcceptPolicy = .always
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 20
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()
    private var pendingLogin: Document?

    func clear() {
        session.invalidateAndCancel()
        pendingLogin = nil
    }

    private func allowed(_ url: URL, method: String) -> Bool {
        guard url.scheme == "https", url.host == "jwglxxfwpt.hebeu.edu.cn", url.port == nil || url.port == 443 else { return false }
        let path = url.path
        if path == "/xtgl/login_slogin.html" { return true }
        if method == "GET" && ["/xtgl/login_getPublicKey.html", "/xtgl/index_initMenu.html", "/kaptcha", "/cdjy/cdjy_cxXqjc.html"].contains(path) { return true }
        if path == "/cdjy/cdjy_cxQtlb.html" { return true }
        if path == "/cdjy/cdjy_cxKxcdlb.html" {
            return method == "GET" || URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.contains(where: { $0.name == "doType" && $0.value == "query" }) == true
        }
        return false
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard let url = request.url, allowed(url, method: request.httpMethod ?? "GET") else {
            completionHandler(nil)
            return
        }
        completionHandler(request)
    }

    private static func encode(_ values: [(String, String)]) -> String {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._*")
        func escape(_ value: String) -> String { value.addingPercentEncoding(withAllowedCharacters: allowed)!.replacingOccurrences(of: "%20", with: "+") }
        return values.map { "\(escape($0.0))=\(escape($0.1))" }.joined(separator: "&")
    }

    private func request(_ path: String, form: [(String, String)]? = nil) async throws -> (Data, String) {
        guard let url = URL(string: SchoolProtocol.origin + path) else { throw SchoolError.message("学校请求地址无效。") }
        let method = form == nil ? "GET" : "POST"
        guard allowed(url, method: method) else { throw SchoolError.message("仅允许连接学校登录及空教室查询接口。") }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        req.httpMethod = method
        req.setValue("KexinRooms/1.0 (iOS; Native)", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json,text/html;q=0.9,*/*;q=0.8", forHTTPHeaderField: "Accept")
        req.setValue("no-cache, no-store", forHTTPHeaderField: "Cache-Control")
        req.setValue(SchoolProtocol.origin + (url.path.hasPrefix("/cdjy/") ? SchoolProtocol.roomsPath : "/xtgl/login_slogin.html"), forHTTPHeaderField: "Referer")
        if let form {
            req.httpBody = Self.encode(form).data(using: .utf8)
            req.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
            req.setValue(SchoolProtocol.origin, forHTTPHeaderField: "Origin")
        }
        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse else { throw SchoolError.message("学校响应无效。") }
        if http.statusCode == 401 { throw SchoolError.authExpired }
        guard http.statusCode == 200, data.count <= 8_000_000 else {
            throw SchoolError.message("教务系统暂时无法响应（HTTP \(http.statusCode)）。")
        }
        return (data, http.value(forHTTPHeaderField: "Content-Type") ?? "")
    }

    private func html(_ path: String, form: [(String, String)]? = nil) async throws -> String {
        let (data, _) = try await request(path, form: form)
        guard let text = String(data: data, encoding: .utf8) else { throw SchoolError.message("学校页面编码无法识别。") }
        return text
    }

    private func json(_ path: String, form: [(String, String)]? = nil) async throws -> [String: Any] {
        let (data, _) = try await request(path, form: form)
        if let text = String(data: data, encoding: .utf8), text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("<") {
            _ = try SchoolProtocol.document(text)
            throw SchoolError.message("学校未返回有效查询数据，本次未显示结果。")
        }
        return try SchoolProtocol.object(data)
    }

    private func captchaShown(_ doc: Document) throws -> Bool {
        guard try doc.getElementById("yzm") != nil else { return false }
        guard let container = try doc.getElementById("yzmDiv") else { return true }
        return !(try container.attr("style")).replacingOccurrences(of: " ", with: "").contains("display:none")
    }

    func captchaImage() async throws -> Data {
        let (data, contentType) = try await request("/kaptcha?time=\(Int(Date().timeIntervalSince1970 * 1000))")
        guard contentType.hasPrefix("image/") else { throw SchoolError.message("验证码图片暂不可用。") }
        return data
    }

    func login(username: String, password: String, captcha: String? = nil) async throws {
        if captcha == nil || pendingLogin == nil {
            pendingLogin = try SwiftSoup.parse(await html("/xtgl/login_slogin.html"))
        }
        guard let loginPage = pendingLogin, try loginPage.select("input[name=yhm]").first() != nil else {
            throw SchoolError.message("学校登录页面已变化。")
        }
        if try captchaShown(loginPage), (captcha ?? "").isEmpty { throw SchoolError.captchaRequired }
        guard let tokenInput = try loginPage.select("input[name=csrftoken]").first() else {
            throw SchoolError.message("登录校验信息缺失。")
        }
        let token = try tokenInput.val()
        guard !token.isEmpty else { throw SchoolError.message("登录校验信息缺失。") }
        let millis = Int(Date().timeIntervalSince1970 * 1000)
        let key = try await json("/xtgl/login_getPublicKey.html?time=\(millis)")
        let encrypted = try Self.encrypt(password, modulus: SchoolProtocol.string(key["modulus"]), exponent: SchoolProtocol.string(key["exponent"]))
        var form = [("csrftoken", token), ("yhm", username), ("mm", encrypted), ("language", "zh_CN"), ("ydType", "")]
        if let captcha, !captcha.isEmpty { form.append(("yzm", captcha)) }
        let response = try SwiftSoup.parse(await html("/xtgl/login_slogin.html?time=\(millis)", form: form))
        if try response.select("input[name=yhm]").first() != nil {
            pendingLogin = response
            if try captchaShown(response) { throw SchoolError.captchaRequired }
            throw SchoolError.message("学校未接受此次登录，请核对账号密码；已停止自动重试。")
        }
        _ = try SchoolProtocol.value(SchoolProtocol.document(await html(SchoolProtocol.roomsPath)), "xnm")
        pendingLogin = nil
    }

    private static func derLength(_ count: Int) -> Data {
        if count < 128 { return Data([UInt8(count)]) }
        if count < 256 { return Data([0x81, UInt8(count)]) }
        return Data([0x82, UInt8((count >> 8) & 255), UInt8(count & 255)])
    }

    private static func derInteger(_ value: Data) -> Data {
        var bytes = Array(value.drop(while: { $0 == 0 }))
        if bytes.isEmpty { bytes = [0] }
        if bytes[0] & 0x80 != 0 { bytes.insert(0, at: 0) }
        return Data([0x02]) + derLength(bytes.count) + Data(bytes)
    }

    private static func encrypt(_ password: String, modulus: String, exponent: String) throws -> String {
        guard let n = Data(base64Encoded: modulus), let e = Data(base64Encoded: exponent), !n.isEmpty, !e.isEmpty else {
            throw SchoolError.message("学校加密公钥无效。")
        }
        let body = derInteger(n) + derInteger(e)
        let publicKeyData = Data([0x30]) + derLength(body.count) + body
        let attributes: [CFString: Any] = [kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeyClass: kSecAttrKeyClassPublic, kSecAttrKeySizeInBits: n.count * 8]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(publicKeyData as CFData, attributes as CFDictionary, &error),
              let encrypted = SecKeyCreateEncryptedData(key, .rsaEncryptionPKCS1, Data(password.utf8) as CFData, &error) else {
            throw SchoolError.message("学校密码加密失败。")
        }
        return (encrypted as Data).base64EncodedString()
    }

    func config() async throws -> SchoolConfig {
        let page = try await html(SchoolProtocol.roomsPath)
        let doc = try SchoolProtocol.document(page)
        let year = try SchoolProtocol.value(doc, "xnm")
        let term = try SchoolProtocol.value(doc, "xqm")
        let metadata = try await json("/cdjy/cdjy_cxXqjc.html?gnmkdm=N2155&" + Self.encode([("xqh_id", SchoolProtocol.campus), ("xnm", year), ("xqm", term)]))
        let termData = try await json("/cdjy/cdjy_cxQtlb.html?gnmkdm=N2155", form: [("xqh_id", SchoolProtocol.campus), ("xnm", year), ("xqm", term), ("flag", "0")])
        return try SchoolProtocol.config(html: page, metadata: metadata, termData: termData)
    }

    func search(date: Date, from: Int, to: Int, building: String, type: String) async throws -> (SchoolConfig, [SchoolRoom], Date) {
        let current = try await config()
        var all = [SchoolRoom]()
        var ids = Set<String>()
        var expectedTotal: Int?
        for index in 1...30 {
            let form = try SchoolProtocol.query(current, date: date, from: from, to: to, building: building, type: type, page: index)
            let response = try await json(SchoolProtocol.roomsPath + "&doType=query", form: form)
            let page = try SchoolProtocol.page(response)
            guard page.current == index, expectedTotal == nil || expectedTotal == page.total else {
                throw SchoolError.message("查询期间学校数据发生变化，请刷新重试。")
            }
            expectedTotal = page.total
            for room in page.rooms {
                guard ids.insert(room.id).inserted else { throw SchoolError.message("学校分页重复，请刷新重试。") }
                all.append(room)
            }
            if index >= page.pages {
                guard all.count == page.total else { throw SchoolError.message("学校数据没有完整返回，请刷新重试。") }
                return (current, all, Date())
            }
            guard !page.rooms.isEmpty else { throw SchoolError.message("学校分页数据不完整。") }
        }
        throw SchoolError.message("返回页数超过安全上限，本次未显示结果。")
    }
}
