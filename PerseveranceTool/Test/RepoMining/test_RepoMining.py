import unittest
from unittest.mock import MagicMock
from pydriller import RepositoryMining

from ...Application.RepoMining.RepoMining import RepoMining

class Test_RepoMining(unittest.TestCase):
    repo_url = "https://github.com/DeltaCappello/Predicting-Vulnerable-Code"
    commitHash = "912fa87667aa3fbaa68c31913790f2ac758ab368"
    allCommit = ["cc6b251f1083df2abf3e08eb53570ca78c3f738e", "4f980e51aecbf8119a02514e9573e5cdd262acb7", "9e1c0912b6d819d2970a15d742eec4687f55e80e", "6e5de9082ab1d8986a31a73709d896880b604be7"]


    #----------------------isEmptyUrl()-------------------------------------------
    """Test if the passed URL is valid"""
    def test_isEmptyUrl_Valid(self):
        result = RepoMining.isEmptyUrl(self, self.repo_url)
        self.assertFalse(result, "https://github.com/DeltaCappello/Predicting-Vulnerable-Code")

    """Test if the passed URL is None"""
    def test_isEmptyUrl_None(self):
        result = RepoMining.isEmptyUrl(self, None)
        self.assertTrue(result, None)

    """Test if the URL is empty"""
    def test_isEmpty_empty(self):
        result = RepoMining.isEmptyUrl(self, "")
        self.assertTrue(result, "")

    #----------------------isEmptyHash()-------------------------------------------

    """Test if the hash of the commit is Valid"""
    def test_isEmptyHash(self):
        result = RepoMining.isEmptyHash(self, self.commitHash)
        self.assertFalse(result, "912fa87667aa3fbaa68c31913790f2ac758ab368")

    """Test if the hash of the commit is None"""
    def test_isEmptyHash(self):
        result = RepoMining.isEmptyHash(self, None)
        self.assertTrue(result, None)

    """Test if the hash of the commit is empty"""
    def test_isEmptyHash(self):
        result = RepoMining.isEmptyHash(self, "")
        self.assertTrue(result, "")

    #----------------------setDefaultDir()-------------------------------------------
    """Test if the default Directory is right for the system that we are using"""
    def test_setDefaultDir(self):
        result = RepoMining.setDefaultDir(self)
        self.assertEqual(result, "/Users/UniSa/Desktop/")

    #----------------------getAllCommit()-------------------------------------------
    """Test if the commit of a specific repository are valid"""
    def test_getAllCommit(self):
        gotAllCommit = []
        result = RepoMining.getAllCommit(RepoMining, "https://github.com/Dariucc07/Sometest")
        for commit in RepositoryMining("https://github.com/Dariucc07/Sometest").traverse_commits():
            gotAllCommit.append(commit.hash)
        self.assertListEqual(result, gotAllCommit)